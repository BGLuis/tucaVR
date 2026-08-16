use oboe::{
    AudioOutputCallback, AudioStreamAsync, AudioStreamBuilder, DataCallbackResult,
    PerformanceMode, SharingMode, Output, Stereo, AudioStream, AudioOutputStream
};
use crossbeam_channel::{Receiver, Sender, bounded};
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

pub struct AudioPlayerCallback {
    receiver: Receiver<f32>,
    // f32 armazenado como bits para poder ser lido sem lock no callback
    // de audio em tempo real (chamado pelo Oboe, nao deve bloquear).
    volume_bits: Arc<AtomicU32>,
}

impl AudioOutputCallback for AudioPlayerCallback {
    type FrameType = (f32, Stereo);

    fn on_audio_ready(
        &mut self,
        _audio_stream: &mut dyn oboe::AudioOutputStreamSafe,
        audio_data: &mut [(f32, f32)],
    ) -> DataCallbackResult {
        let ptr = audio_data.as_mut_ptr() as *mut f32;
        let len = audio_data.len() * 2;
        let slice = unsafe { std::slice::from_raw_parts_mut(ptr, len) };

        let volume = f32::from_bits(self.volume_bits.load(Ordering::Relaxed));
        for sample in slice.iter_mut() {
            *sample = self.receiver.try_recv().unwrap_or(0.0) * volume;
        }

        DataCallbackResult::Continue
    }
}

pub struct AudioOutput {
    stream: AudioStreamAsync<Output, AudioPlayerCallback>,
    sender: Sender<f32>,
    volume_bits: Arc<AtomicU32>,
}

impl AudioOutput {
    pub fn new() -> Result<Self, oboe::Error> {
        let (sender, receiver) = bounded(48000); // 0.5 sec of stereo audio buffer
        let volume_bits = Arc::new(AtomicU32::new(1.0f32.to_bits()));
        let callback = AudioPlayerCallback { receiver, volume_bits: volume_bits.clone() };

        let builder = AudioStreamBuilder::default();
        let stream = builder
            .set_direction::<Output>()
            .set_performance_mode(PerformanceMode::LowLatency)
            .set_sharing_mode(SharingMode::Exclusive)
            .set_format::<f32>()
            .set_channel_count::<Stereo>()
            .set_sample_rate(48000)
            .set_usage(oboe::Usage::Media)
            .set_content_type(oboe::ContentType::Music)
            .set_callback(callback)
            .open_stream()?;

        Ok(Self { stream, sender, volume_bits })
    }

    pub fn push_samples(&mut self, samples: &[f32]) {
        for &sample in samples {
            let _ = self.sender.send(sample);
        }
    }

    pub fn get_sender(&self) -> Sender<f32> {
        self.sender.clone()
    }

    pub fn set_volume(&self, volume: f32) {
        self.volume_bits.store(volume.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
    }

    pub fn get_volume(&self) -> f32 {
        f32::from_bits(self.volume_bits.load(Ordering::Relaxed))
    }

    pub fn start(&mut self) -> Result<(), oboe::Error> {
        self.stream.start()
    }

    pub fn pause(&mut self) -> Result<(), oboe::Error> {
        self.stream.pause()
    }

    pub fn stop(&mut self) -> Result<(), oboe::Error> {
        self.stream.stop()
    }
}
