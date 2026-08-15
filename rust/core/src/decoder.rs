use ndk::media::media_codec::{MediaCodec, MediaCodecDirection, DequeuedInputBufferResult, DequeuedOutputBufferInfoResult};
use ndk::media::media_format::MediaFormat;
use ndk::native_window::NativeWindow;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

pub struct HwDecoder {
    codec: Option<MediaCodec>,
    // Contadores de instrumentacao (docs/DEBUGGING.md) — Arc porque o
    // HwDecoder e movido inteiro para dentro da thread de decode
    // (playback.rs); PlaybackController guarda um clone do Arc pra ler
    // estes valores de fora sem lock. Ver metrics().
    frames_output: Arc<AtomicU64>,
    frames_dropped: Arc<AtomicU64>,
}

unsafe impl Send for HwDecoder {}
unsafe impl Sync for HwDecoder {}

impl HwDecoder {
    pub fn new(mime: &str) -> Result<Self, String> {
        let codec = MediaCodec::from_decoder_type(mime)
            .ok_or_else(|| format!("Failed to create MediaCodec for mime: {}", mime))?;
        Ok(Self { codec: Some(codec), frames_output: Arc::new(AtomicU64::new(0)), frames_dropped: Arc::new(AtomicU64::new(0)) })
    }

    /// Clones dos contadores de saida real do MediaCodec — `frames_output`
    /// incrementa em TODO buffer de saida desenfileirado (independente do
    /// callback de sync decidir renderizar ou nao); `frames_dropped` conta
    /// so os descartados por atraso. Investigacao desta sessao
    /// (docs/NETWORK-IO-PERFORMANCE.md) achou que `TextureOutput::frames_decoded`
    /// so conta frames RENDERIZADOS — bom pra comparar contra o vidFps do
    /// C++, mas inutil pra saber se o decoder em si esta produzindo no
    /// ritmo certo quando o gargalo esta a montante (rede/demux).
    pub fn metrics(&self) -> (Arc<AtomicU64>, Arc<AtomicU64>) {
        (self.frames_output.clone(), self.frames_dropped.clone())
    }

    pub fn configure(&mut self, format: &MediaFormat, window: Option<&NativeWindow>) -> Result<(), String> {
        if let Some(codec) = &self.codec {
            codec.configure(format, window, MediaCodecDirection::Decoder)
                .map_err(|e| format!("Failed to configure codec: {:?}", e))?;
            Ok(())
        } else {
            Err("Codec not initialized".to_string())
        }
    }

    pub fn start(&self) -> Result<(), String> {
        if let Some(codec) = &self.codec {
            codec.start().map_err(|e| format!("Failed to start codec: {:?}", e))?;
            Ok(())
        } else {
            Err("Codec not initialized".to_string())
        }
    }

    pub fn stop(&self) -> Result<(), String> {
        if let Some(codec) = &self.codec {
            let _ = codec.stop();
            Ok(())
        } else {
            Err("Codec not initialized".to_string())
        }
    }

    /// `should_continue` e checado a cada volta do loop de retry (quando o
    /// MediaCodec esta com o buffer de entrada cheio). Sem isso, se o
    /// codec nunca liberar espaco (decoder travado, app fechando, etc.),
    /// este loop rodaria para sempre — e como quem chama isso e uma
    /// thread que agora pode ser esperada via `join()` (ver
    /// PlaybackSession::stop_and_join), um travamento aqui trava quem
    /// estiver esperando essa thread tambem.
    pub fn decode_packet<F, A, S>(&self, data: &[u8], pts: i64, flags: u32, mut sync_callback: F, mut after_release: A, should_continue: S) -> Result<bool, String>
    where
        F: FnMut(i64) -> bool,
        A: FnMut(),
        S: Fn() -> bool,
    {
        let codec = self.codec.as_ref().ok_or("Codec not initialized")?;
        let mut released_any = false;

        loop {
            if !should_continue() {
                return Ok(released_any);
            }
            match codec.dequeue_input_buffer(Duration::from_millis(5)) {
                Ok(DequeuedInputBufferResult::Buffer(mut buf)) => {
                    let slice = buf.buffer_mut();
                    let len = data.len().min(slice.len());
                    for i in 0..len {
                        slice[i].write(data[i]);
                    }
                    
                    codec.queue_input_buffer(buf, 0, len, pts as u64, flags)
                        .map_err(|e| format!("queue_input_buffer failed: {:?}", e))?;
                        
                    if self.release_output_frames_with_sync(&mut sync_callback, &mut after_release) {
                        released_any = true;
                    }
                    return Ok(released_any);
                }
                Ok(DequeuedInputBufferResult::TryAgainLater) => {
                    // Decoder is full. Pull output frames to free up input buffers.
                    if self.release_output_frames_with_sync(&mut sync_callback, &mut after_release) {
                        released_any = true;
                    } else {
                        // Avoid busy loop if decoder is stuck
                        std::thread::sleep(Duration::from_millis(5));
                    }
                }
                Err(e) => {
                    return Err(format!("dequeue_input_buffer error: {:?}", e));
                }
            }
        }
    }
    
    pub fn release_output_frames_with_sync<F, A>(&self, mut sync_callback: F, mut after_release: A) -> bool
    where
        F: FnMut(i64) -> bool,
        A: FnMut(),
    {
        let mut released = false;
        if let Some(codec) = &self.codec {
            loop {
                match codec.dequeue_output_buffer(Duration::from_millis(0)) {
                    Ok(DequeuedOutputBufferInfoResult::Buffer(buf)) => {
                        let pts = buf.info().presentation_time_us();
                        self.frames_output.fetch_add(1, Ordering::Relaxed);
                        let should_render = sync_callback(pts);
                        let _ = codec.release_output_buffer(buf, should_render);
                        if should_render {
                            after_release();
                        } else {
                            self.frames_dropped.fetch_add(1, Ordering::Relaxed);
                        }
                        released = true;
                    }
                    Ok(DequeuedOutputBufferInfoResult::TryAgainLater) => {
                        break;
                    }
                    Ok(_) => continue,
                    Err(_) => break,
                }
            }
        }
        released
    }

    pub fn release_output_frames(&self) {
        self.release_output_frames_with_sync(|_| true, || {});
    }
}
