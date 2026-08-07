use ndk::media::media_codec::{MediaCodec, MediaCodecDirection, DequeuedInputBufferResult, DequeuedOutputBufferInfoResult};
use ndk::media::media_format::MediaFormat;
use ndk::native_window::NativeWindow;
use std::time::Duration;

pub struct HwDecoder {
    codec: Option<MediaCodec>,
}

unsafe impl Send for HwDecoder {}
unsafe impl Sync for HwDecoder {}

impl HwDecoder {
    pub fn new(mime: &str) -> Result<Self, String> {
        let codec = MediaCodec::from_decoder_type(mime)
            .ok_or_else(|| format!("Failed to create MediaCodec for mime: {}", mime))?;
        Ok(Self { codec: Some(codec) })
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

    pub fn decode_packet<F, A>(&self, data: &[u8], pts: i64, flags: u32, mut sync_callback: F, mut after_release: A) -> Result<bool, String> 
    where 
        F: FnMut(i64),
        A: FnMut(),
    {
        let codec = self.codec.as_ref().ok_or("Codec not initialized")?;
        let mut released_any = false;
        
        loop {
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
        F: FnMut(i64),
        A: FnMut(),
    {
        let mut released = false;
        if let Some(codec) = &self.codec {
            loop {
                match codec.dequeue_output_buffer(Duration::from_millis(0)) {
                    Ok(DequeuedOutputBufferInfoResult::Buffer(buf)) => {
                        let pts = buf.info().presentation_time_us();
                        sync_callback(pts);
                        let _ = codec.release_output_buffer(buf, true);
                        after_release();
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
        self.release_output_frames_with_sync(|_| {}, || {});
    }
}
