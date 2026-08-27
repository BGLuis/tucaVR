use ndk::media::image_reader::{ImageReader, AcquireResult, Image};
use ndk::hardware_buffer::{HardwareBuffer, HardwareBufferUsage};
use ndk::media::image_reader::ImageFormat;
use ndk::native_window::NativeWindow;

pub struct TextureOutput {
    pub reader: Option<ImageReader>,
    pub current_image: Option<Image>,
    pub current_buffer: Option<HardwareBuffer>,
    // Debug (docs/DEBUGGING.md): conta frames REALMENTE apresentados aqui
    // (ImageReader adquiriu um buffer novo), na thread de decode —
    // independente de quantas vezes o C++ chama get_current_video_frame()
    // pra observar isso. Renomeado de "decoded" pra "apresentados" nesta
    // sessao (docs/NETWORK-IO-PERFORMANCE.md): so incrementa quando o
    // callback de sync decide RENDERIZAR o frame, entao nao mede o
    // throughput real do MediaCodec — pra isso ver HwDecoder::metrics()
    // (frames_output), que conta todo buffer de saida desenfileirado.
    pub frames_decoded: u64,
}

unsafe impl Send for TextureOutput {}
unsafe impl Sync for TextureOutput {}

impl TextureOutput {
    pub fn new() -> Self {
        Self { reader: None, current_image: None, current_buffer: None, frames_decoded: 0 }
    }

    pub fn allocate(&mut self, width: u32, height: u32) -> Result<(), String> {
        let usage = HardwareBufferUsage::GPU_SAMPLED_IMAGE;
        let reader = ImageReader::new_with_usage(
            width as i32,
            height as i32,
            ImageFormat::PRIVATE, // 34 = IMPLEMENTATION_DEFINED, best for surface texturing
            usage,
            4,
        ).map_err(|e| format!("Failed to create ImageReader: {:?}", e))?;
        
        self.current_image = None;
        self.current_buffer = None;
        self.reader = Some(reader);
        Ok(())
    }

    pub fn clear(&mut self) {
        self.current_buffer = None;
        self.current_image = None;
    }

    pub fn get_window(&self) -> Option<NativeWindow> {
        self.reader.as_ref().and_then(|r| r.window().ok())
    }

    pub fn acquire_latest_buffer(&mut self) -> Option<&HardwareBuffer> {
        if let Some(reader) = &mut self.reader {
            match reader.acquire_latest_image() {
                Ok(AcquireResult::Image(image)) => {
                    match image.hardware_buffer() {
                        Ok(buffer) => {
                            // Log por frame removido nesta sessao
                            // (docs/NETWORK-IO-PERFORMANCE.md): disparava a
                            // ~30-60Hz, na thread de decode, dentro do mutex
                            // texture_output que o loop de render tambem
                            // disputa — custo real por frame por um log que
                            // ninguem lia.
                            self.current_buffer = Some(buffer);
                            self.current_image = Some(image);
                            self.frames_decoded = self.frames_decoded.wrapping_add(1);
                        }
                        Err(e) => {
                            crate::log_error!("hardware_buffer error: {:?}", e);
                        }
                    }
                }
                Ok(AcquireResult::NoBufferAvailable) | Ok(AcquireResult::MaxImagesAcquired) => {
                    // Normal, no image ready yet or max images reached
                }
                Err(e) => {
                    crate::log_error!("acquire_latest_image error: {:?}", e);
                }
            }
        }
        self.current_buffer.as_ref()
    }
}
