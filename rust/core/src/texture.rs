use ndk::media::image_reader::{ImageReader, AcquireResult, Image};
use ndk::hardware_buffer::{HardwareBuffer, HardwareBufferUsage};
use ndk::media::image_reader::ImageFormat;
use ndk::native_window::NativeWindow;

pub struct TextureOutput {
    pub reader: Option<ImageReader>,
    pub current_image: Option<Image>,
    pub current_buffer: Option<HardwareBuffer>,
}

unsafe impl Send for TextureOutput {}
unsafe impl Sync for TextureOutput {}

impl TextureOutput {
    pub fn new() -> Self {
        Self { reader: None, current_image: None, current_buffer: None }
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
