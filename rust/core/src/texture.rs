use ndk::media::image_reader::{ImageReader, AcquireResult, Image};
use ndk::hardware_buffer::{HardwareBuffer, HardwareBufferUsage};
use ndk::media::image_reader::ImageFormat;
use ndk::native_window::NativeWindow;

pub struct TextureOutput {
    pub reader: Option<ImageReader>,
    pub current_image: Option<Image>,
    pub current_buffer: Option<HardwareBuffer>,
    // Debug (docs/DEBUGGING.md): conta frames REALMENTE decodificados/
    // adquiridos aqui, na thread de decode — independente de quantas vezes
    // o C++ chama get_current_video_frame() pra observar isso. Existe pra
    // isolar "o decode esta lento" de "o C++ so esta perdendo frames que o
    // decode ja produziu" quando o vidFps calculado do lado C++ (que so
    // enxerga o que consegue observar por polling) parecer baixo — os dois
    // numeros devem bater; se nao baterem, o problema e do lado do consumo
    // (import/cache Vulkan, taxa de polling), nao do decode em si.
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

    pub fn get_window(&self) -> Option<NativeWindow> {
        self.reader.as_ref().and_then(|r| r.window().ok())
    }

    pub fn acquire_latest_buffer(&mut self) -> Option<&HardwareBuffer> {
        if let Some(reader) = &mut self.reader {
            match reader.acquire_latest_image() {
                Ok(AcquireResult::Image(image)) => {
                    match image.hardware_buffer() {
                        Ok(buffer) => {
                            unsafe {
                                let tag = std::ffi::CString::new("VRPlayer_Rust").unwrap();
                                let msg = std::ffi::CString::new("Successfully acquired a new frame!").unwrap();
                                ndk_sys::__android_log_print(4, tag.as_ptr(), msg.as_ptr());
                            }
                            self.current_buffer = Some(buffer);
                            self.current_image = Some(image);
                            self.frames_decoded = self.frames_decoded.wrapping_add(1);
                        }
                        Err(e) => {
                            unsafe {
                                let tag = std::ffi::CString::new("VRPlayer_Rust").unwrap();
                                let msg = std::ffi::CString::new(format!("hardware_buffer error: {:?}", e)).unwrap();
                                ndk_sys::__android_log_print(6, tag.as_ptr(), msg.as_ptr());
                            }
                        }
                    }
                }
                Ok(AcquireResult::NoBufferAvailable) | Ok(AcquireResult::MaxImagesAcquired) => {
                    // Normal, no image ready yet or max images reached
                }
                Err(e) => {
                    unsafe {
                        let tag = std::ffi::CString::new("VRPlayer_Rust").unwrap();
                        let msg = std::ffi::CString::new(format!("acquire_latest_image error: {:?}", e)).unwrap();
                        ndk_sys::__android_log_print(6, tag.as_ptr(), msg.as_ptr());
                    }
                }
            }
        }
        self.current_buffer.as_ref()
    }
}
