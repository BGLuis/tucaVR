use ndk::media::media_codec::{MediaCodec, MediaCodecDirection, DequeuedInputBufferResult, DequeuedOutputBufferInfoResult, OutputBuffer};
use ndk::media::media_format::MediaFormat;
use ndk::native_window::NativeWindow;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

// Coordena a JANELA de criacao/configuracao/start de sessoes MediaCodec entre
// a decodificacao principal (playback.rs) e a geracao de tira de scrub em
// background (thumbnail.rs) — achado real em hardware (Quest 3, ver
// bridge/src/lib.rs::playback_is_active): duas sessoes MediaCodec sendo
// CRIADAS/INICIADAS ao mesmo tempo derrubam o driver de video. So serializa
// o setup, nao a decodificacao: uma vez que ambas as sessoes ja estao
// rodando (start() retornou), decode_packet() de cada uma prossegue livre,
// concorrente. Hipotese ainda nao validada em hardware — ver comentario em
// new_configured_and_started.
static SESSION_SETUP_LOCK: Mutex<()> = Mutex::new(());

/// Mime do MediaCodec de hardware + se o stream usa framing NAL (avcC/hvcC,
/// precisa converter pra Annex-B antes de decodificar) pro `codec_id` do
/// ffmpeg. Compartilhado entre `playback.rs` (reproducao real) e
/// `thumbnail.rs` (trilha de scrub por hardware) — extraido daqui pra nao
/// duplicar o mapeamento e arriscar as duas copias divergirem se um codec
/// novo for adicionado num lugar so.
pub fn mime_for_codec_id(id: ffmpeg_next::codec::Id) -> Result<(&'static str, bool), String> {
    match id {
        ffmpeg_next::codec::Id::H264 => Ok((media_logic::codec::MIME_H264, true)),
        ffmpeg_next::codec::Id::HEVC => Ok((media_logic::codec::MIME_HEVC, true)),
        ffmpeg_next::codec::Id::VP9 => Ok((media_logic::codec::MIME_VP9, false)),
        ffmpeg_next::codec::Id::AV1 => Ok((media_logic::codec::MIME_AV1, false)),
        other => Err(format!(
            "Unsupported video codec: {:?} (H.264/H.265/VP9/AV1 sao suportados)",
            other
        )),
    }
}

pub struct HwDecoder {
    codec: Option<MediaCodec>,
    // Contadores de instrumentacao (docs/DEBUGGING.md) — Arc porque o
    // HwDecoder e movido inteiro para dentro da thread de decode
    // (playback.rs); PlaybackController guarda um clone do Arc pra ler
    // estes valores de fora sem lock. Ver metrics().
    frames_output: Arc<AtomicU64>,
    frames_dropped: Arc<AtomicU64>,
    // F4 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md): decode_packet() abaixo retorna
    // Result, mas o chamador em playback.rs descartava com `let _ = ...` — sem contador, um
    // erro de decode persistente era indistinguivel de "sem erro nenhum" na telemetria.
    decode_errors: Arc<AtomicU64>,
}

unsafe impl Send for HwDecoder {}
unsafe impl Sync for HwDecoder {}

impl HwDecoder {
    pub fn new(mime: &str) -> Result<Self, String> {
        let codec = MediaCodec::from_decoder_type(mime)
            .ok_or_else(|| {
                if mime == media_logic::codec::MIME_AV1 || mime == media_logic::codec::MIME_VP9 {
                    format!("O dispositivo não possui suporte de hardware para este codec ({})", mime)
                } else {
                    format!("Failed to create MediaCodec for mime: {}", mime)
                }
            })?;
        Ok(Self {
            codec: Some(codec),
            frames_output: Arc::new(AtomicU64::new(0)),
            frames_dropped: Arc::new(AtomicU64::new(0)),
            decode_errors: Arc::new(AtomicU64::new(0)),
        })
    }

    /// Clones dos contadores de saida real do MediaCodec — `frames_output`
    /// incrementa em TODO buffer de saida desenfileirado (independente do
    /// callback de sync decidir renderizar ou nao); `frames_dropped` conta
    /// so os descartados por atraso. Investigacao desta sessao
    /// (docs/NETWORK-IO-PERFORMANCE.md) achou que `TextureOutput::frames_decoded`
    /// so conta frames RENDERIZADOS — bom pra comparar contra o vidFps do
    /// C++, mas inutil pra saber se o decoder em si esta produzindo no
    /// ritmo certo quando o gargalo esta a montante (rede/demux).
    /// `decode_errors` (F4): incrementado por `decode_packet` em qualquer um dos seus
    /// caminhos de erro (codec nao inicializado, queue/dequeue de buffer falhando).
    pub fn metrics(&self) -> (Arc<AtomicU64>, Arc<AtomicU64>, Arc<AtomicU64>) {
        (self.frames_output.clone(), self.frames_dropped.clone(), self.decode_errors.clone())
    }

    /// new()+configure()+start() atomicamente sob SESSION_SETUP_LOCK — ver
    /// comentario no lock acima. Usar isto em vez de chamar os tres
    /// separadamente sempre que a chamada puder acontecer concorrente com
    /// outra sessao MediaCodec (decode principal vs scrub em background).
    pub fn new_configured_and_started(
        mime: &str,
        format: &MediaFormat,
        window: Option<&NativeWindow>,
    ) -> Result<Self, String> {
        let _guard = SESSION_SETUP_LOCK.lock().unwrap();
        let mut decoder = Self::new(mime)?;
        decoder.configure(format, window)?;
        decoder.start()?;
        Ok(decoder)
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

    /// Descarta estado de referencia interno sem destruir o codec — permite
    /// reaproveitar o mesmo MediaCodec num seek. So entre start() e stop();
    /// o proximo pacote apos o flush precisa ser uma keyframe.
    pub fn flush(&self) -> Result<(), String> {
        let codec = self.codec.as_ref().ok_or("Codec not initialized")?;
        codec.flush().map_err(|e| format!("Failed to flush codec: {:?}", e))
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
        let codec = self.codec.as_ref().ok_or_else(|| {
            self.decode_errors.fetch_add(1, Ordering::Relaxed);
            "Codec not initialized".to_string()
        })?;
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
                        .map_err(|e| {
                            self.decode_errors.fetch_add(1, Ordering::Relaxed);
                            format!("queue_input_buffer failed: {:?}", e)
                        })?;

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
                    self.decode_errors.fetch_add(1, Ordering::Relaxed);
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

    /// Enfileira UM pacote de entrada, com o mesmo retry de
    /// `decode_packet` quando o MediaCodec esta com a entrada cheia — mas,
    /// diferente de `decode_packet`, NAO drena nem decide nada sobre a
    /// saida: isso fica por conta de `try_dequeue_output`/`release_output`,
    /// chamados separadamente por quem usa isto (ver o loop de video em
    /// `playback.rs`). Essa separacao e o que permite continuar
    /// alimentando o proximo pacote em vez de ficar preso num sleep unico
    /// esperando a hora de mostrar o frame anterior.
    ///
    /// `on_stalled` e chamado a cada retry enquanto a entrada estiver
    /// cheia (TryAgainLater) — o codigo antigo, nesse caso, chamava
    /// `release_output_frames_with_sync` pra tentar abrir espaco liberando
    /// saida pendente; aqui isso vira responsabilidade de quem chama (via
    /// este hook), porque agora e quem chama que sabe quais frames ja
    /// decodificados estao pendentes de apresentacao e pode decidir
    /// liberar o mais antigo cedo (aceitando pequena imprecisao de sync)
    /// como valvula de alivio antes de travar de verdade.
    ///
    /// `pts_us`: timestamp de apresentacao estritamente em MICROSSEGUNDOS (us),
    /// conforme exigido pelo `AMediaCodec_queueInputBuffer` do Android NDK.
    pub fn feed_input(
        &self,
        data: &[u8],
        pts_us: i64,
        flags: u32,
        mut on_stalled: impl FnMut(),
        should_continue: impl Fn() -> bool,
    ) -> Result<(), String> {
        let codec = self.codec.as_ref().ok_or_else(|| {
            self.decode_errors.fetch_add(1, Ordering::Relaxed);
            "Codec not initialized".to_string()
        })?;

        loop {
            if !should_continue() {
                return Ok(());
            }
            match codec.dequeue_input_buffer(Duration::from_millis(5)) {
                Ok(DequeuedInputBufferResult::Buffer(mut buf)) => {
                    let slice = buf.buffer_mut();
                    let len = data.len().min(slice.len());
                    for i in 0..len {
                        slice[i].write(data[i]);
                    }

                    codec.queue_input_buffer(buf, 0, len, pts_us as u64, flags)
                        .map_err(|e| {
                            self.decode_errors.fetch_add(1, Ordering::Relaxed);
                            format!("queue_input_buffer failed: {:?}", e)
                        })?;
                    return Ok(());
                }
                Ok(DequeuedInputBufferResult::TryAgainLater) => {
                    on_stalled();
                    std::thread::sleep(Duration::from_millis(5));
                }
                Err(e) => {
                    self.decode_errors.fetch_add(1, Ordering::Relaxed);
                    return Err(format!("dequeue_input_buffer error: {:?}", e));
                }
            }
        }
    }

    /// Tenta pegar UM frame de saida pronto, SEM liberar — nao bloqueia
    /// (timeout 0). `OutputFormatChanged`/`OutputBuffersChanged` sao
    /// absorvidos aqui (chamando dequeue de novo), como o loop antigo de
    /// `release_output_frames_with_sync` ja fazia; so `TryAgainLater`
    /// (nada pronto agora) ou erro devolvem `None`. O frame devolvido fica
    /// seguro no proprio pool de buffers de saida do MediaCodec ate quem
    /// chamou decidir a hora certa de liberar via `release_output` — nao e
    /// copiado, entao a profundidade de quantos ficam pendentes de fora e
    /// naturalmente limitada por esse pool (`dequeue_input_buffer` comeca
    /// a bloquear quando ele se esgota), sem precisar de um teto proprio.
    pub fn try_dequeue_output(&self) -> Option<OutputBuffer<'_>> {
        let codec = self.codec.as_ref()?;
        loop {
            match codec.dequeue_output_buffer(Duration::from_millis(0)) {
                Ok(DequeuedOutputBufferInfoResult::Buffer(buf)) => {
                    self.frames_output.fetch_add(1, Ordering::Relaxed);
                    return Some(buf);
                }
                Ok(DequeuedOutputBufferInfoResult::TryAgainLater) => return None,
                Ok(_) => continue,
                Err(_) => return None,
            }
        }
    }

    /// Libera (mostrando ou descartando) um frame previamente obtido por
    /// `try_dequeue_output`, na hora decidida por quem chama (ver
    /// `media_logic::frame_timing` e o loop de video em `playback.rs`).
    /// `render=false` conta em `frames_dropped`, como o caminho antigo em
    /// `release_output_frames_with_sync`.
    pub fn release_output(&self, buf: OutputBuffer<'_>, render: bool) -> Result<(), String> {
        let codec = self.codec.as_ref().ok_or("Codec not initialized")?;
        codec.release_output_buffer(buf, render)
            .map_err(|e| format!("release_output_buffer failed: {:?}", e))?;
        if !render {
            self.frames_dropped.fetch_add(1, Ordering::Relaxed);
        }
        Ok(())
    }

    /// Enfileira `data` e tenta pegar UM frame de saida cru (YUV, sem
    /// conversao) — so usado pelo caminho sem Surface de
    /// `thumbnail::generate_strip_hw` (`configure` chamado com
    /// `window: None`). Diferente de `decode_packet`, nao serve pra
    /// reproducao continua: decoders com atraso de reordenacao (B-frames)
    /// podem nao ter saida pronta logo apos este pacote — quem chama
    /// alimenta o proximo pacote e tenta de novo (mesmo padrao de
    /// `thumbnail::decode_and_scale` pro decode por software).
    pub fn decode_one_frame_raw(&self, data: &[u8], pts: i64, flags: u32) -> Result<Option<RawFrame>, String> {
        let codec = self.codec.as_ref().ok_or("Codec not initialized")?;

        let mut queued = false;
        for _ in 0..20 {
            match codec.dequeue_input_buffer(Duration::from_millis(5)) {
                Ok(DequeuedInputBufferResult::Buffer(mut buf)) => {
                    let slice = buf.buffer_mut();
                    let len = data.len().min(slice.len());
                    for i in 0..len {
                        slice[i].write(data[i]);
                    }
                    codec.queue_input_buffer(buf, 0, len, pts as u64, flags)
                        .map_err(|e| format!("queue_input_buffer failed: {:?}", e))?;
                    queued = true;
                    break;
                }
                Ok(DequeuedInputBufferResult::TryAgainLater) => {
                    std::thread::sleep(Duration::from_millis(5));
                }
                Err(e) => return Err(format!("dequeue_input_buffer error: {:?}", e)),
            }
        }
        if !queued {
            return Err("input buffer never freed up".to_string());
        }

        match codec.dequeue_output_buffer(Duration::from_millis(10)) {
            Ok(DequeuedOutputBufferInfoResult::Buffer(buf)) => {
                let format = buf.format();
                let raw = RawFrame {
                    data: buf.buffer().to_vec(),
                    color_format: format.i32("color-format").unwrap_or(-1),
                    width: format.i32("width").unwrap_or(0),
                    height: format.i32("height").unwrap_or(0),
                    stride: format.i32("stride").unwrap_or(0),
                    slice_height: format.i32("slice-height").unwrap_or(0),
                };
                let _ = codec.release_output_buffer(buf, false);
                Ok(Some(raw))
            }
            Ok(_) => Ok(None),
            Err(_) => Ok(None),
        }
    }
}

/// Bytes YUV crus de UM frame de saida do MediaCodec sem Surface, junto do
/// color-format/stride/slice-height negociados em runtime — `buffer()`/
/// `format()` do MediaCodec ficam invalidos apos `release_output_buffer`,
/// entao os bytes sao copiados antes de liberar (ver `decode_one_frame_raw`).
pub struct RawFrame {
    pub data: Vec<u8>,
    pub color_format: i32,
    pub width: i32,
    pub height: i32,
    pub stride: i32,
    pub slice_height: i32,
}
