//! Módulo de Processamento de Áudio Espacial e Virtualização 3D — PHASE-0.3, Seções 3 e 4.
//!
//! Integra virtualização multicanal (5.1 e 7.1), decodificação Ambisonics (FOA/SOA),
//! filtragem LFE e downmix estéreo em um único pipeline de alto desempenho.

pub mod ambisonics;
pub mod biquad;
pub mod hrtf;
pub mod quaternion;
pub mod surround;

pub use ambisonics::{AmbisonicsDecoder, ChannelOrdering, Normalization};
pub use biquad::BiquadFilter;
pub use hrtf::{FirConvolver, HrtfDataset, HrtfPair};
pub use quaternion::{Quat, Vec3};
pub use surround::{SurroundLayout, SurroundVirtualizer};

/// Layout de canais de áudio detectado no stream de mídia.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioChannelLayout {
    Mono,
    Stereo,
    Surround5_1,
    Surround7_1,
    AmbisonicsFoa(ChannelOrdering, Normalization),
    Unknown(u32),
}

impl AudioChannelLayout {
    pub fn channels(self) -> u32 {
        match self {
            AudioChannelLayout::Mono => 1,
            AudioChannelLayout::Stereo => 2,
            AudioChannelLayout::Surround5_1 => 6,
            AudioChannelLayout::Surround7_1 => 8,
            AudioChannelLayout::AmbisonicsFoa(..) => 4,
            AudioChannelLayout::Unknown(ch) => ch,
        }
    }

    /// Identifica o layout com base na contagem de canais e pistas de tags de metadados.
    pub fn from_channel_count_and_tags(channels: u32, is_ambisonics_hint: bool) -> Self {
        match channels {
            1 => AudioChannelLayout::Mono,
            2 => AudioChannelLayout::Stereo,
            4 => {
                if is_ambisonics_hint {
                    AudioChannelLayout::AmbisonicsFoa(ChannelOrdering::Acn, Normalization::Sn3d)
                } else {
                    // Sem tag específica, 4 canais pode ser quadrafônico ou Ambisonics padrão YouTube
                    AudioChannelLayout::AmbisonicsFoa(ChannelOrdering::Acn, Normalization::Sn3d)
                }
            }
            6 => AudioChannelLayout::Surround5_1,
            8 => AudioChannelLayout::Surround7_1,
            other => AudioChannelLayout::Unknown(other),
        }
    }

    /// Retorna o nome amigável para exibição em badges da UI.
    pub fn display_name(&self) -> &'static str {
        match self {
            AudioChannelLayout::Mono => "Mono",
            AudioChannelLayout::Stereo => "Stereo",
            AudioChannelLayout::Surround5_1 => "5.1 Surround",
            AudioChannelLayout::Surround7_1 => "7.1 Surround",
            AudioChannelLayout::AmbisonicsFoa(..) => "Ambisonics 360°",
            AudioChannelLayout::Unknown(..) => "Multi-channel",
        }
    }
}

/// Modo de processamento de áudio espacial configurável pelo usuário.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum SpatialAudioMode {
    /// Desativa processamento espacial (pass-through estéreo / downmix simples se multicanal).
    DirectStereo = 0,
    /// Virtualização binaural imersiva com HRTF e posicionamento 3D.
    VirtualizedBinaural = 1,
    /// Downmix estéreo matricial clássico de baixo custo (ITU-R BS.775-1).
    SimpleDownmix = 2,
}

impl From<u32> for SpatialAudioMode {
    fn from(val: u32) -> Self {
        match val {
            0 => SpatialAudioMode::DirectStereo,
            1 => SpatialAudioMode::VirtualizedBinaural,
            2 => SpatialAudioMode::SimpleDownmix,
            _ => SpatialAudioMode::VirtualizedBinaural,
        }
    }
}
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

pub static GLOBAL_HEAD_ROT_X: AtomicU32 = AtomicU32::new(0);
pub static GLOBAL_HEAD_ROT_Y: AtomicU32 = AtomicU32::new(0);
pub static GLOBAL_HEAD_ROT_Z: AtomicU32 = AtomicU32::new(0);
pub static GLOBAL_HEAD_ROT_W: AtomicU32 = AtomicU32::new(1065353216); // 1.0f32.to_bits()

pub static GLOBAL_SPATIAL_MODE: AtomicU32 = AtomicU32::new(1); // 1 = VirtualizedBinaural
pub static GLOBAL_SPATIAL_HEAD_TRACKING: AtomicBool = AtomicBool::new(true);

/// Atualiza a orientação da cabeça a partir do render loop OpenXR (lock-free).
pub fn set_global_head_orientation(x: f32, y: f32, z: f32, w: f32) {
    GLOBAL_HEAD_ROT_X.store(x.to_bits(), Ordering::Relaxed);
    GLOBAL_HEAD_ROT_Y.store(y.to_bits(), Ordering::Relaxed);
    GLOBAL_HEAD_ROT_Z.store(z.to_bits(), Ordering::Relaxed);
    GLOBAL_HEAD_ROT_W.store(w.to_bits(), Ordering::Relaxed);
}

/// Lê a orientação atual da cabeça sem locks.
pub fn get_global_head_orientation() -> Quat {
    Quat::new(
        f32::from_bits(GLOBAL_HEAD_ROT_X.load(Ordering::Relaxed)),
        f32::from_bits(GLOBAL_HEAD_ROT_Y.load(Ordering::Relaxed)),
        f32::from_bits(GLOBAL_HEAD_ROT_Z.load(Ordering::Relaxed)),
        f32::from_bits(GLOBAL_HEAD_ROT_W.load(Ordering::Relaxed)),
    )
}

pub fn set_global_spatial_mode(mode: SpatialAudioMode) {
    GLOBAL_SPATIAL_MODE.store(mode as u32, Ordering::Relaxed);
}

pub fn get_global_spatial_mode() -> SpatialAudioMode {
    SpatialAudioMode::from(GLOBAL_SPATIAL_MODE.load(Ordering::Relaxed))
}

pub fn set_global_head_tracking_enabled(enabled: bool) {
    GLOBAL_SPATIAL_HEAD_TRACKING.store(enabled, Ordering::Relaxed);
}

pub fn get_global_head_tracking_enabled() -> bool {
    GLOBAL_SPATIAL_HEAD_TRACKING.load(Ordering::Relaxed)
}

/// Processador central de áudio espacial para sessões de reprodução.
pub struct SpatialAudioProcessor {
    layout: AudioChannelLayout,
    mode: SpatialAudioMode,
    head_tracking_enabled: bool,
    sample_rate: f32,
    surround_virt: Option<SurroundVirtualizer>,
    ambisonics_dec: Option<AmbisonicsDecoder>,
}

impl SpatialAudioProcessor {
    pub fn new(layout: AudioChannelLayout, sample_rate: f32) -> Self {
        let mut proc = Self {
            layout,
            mode: SpatialAudioMode::VirtualizedBinaural,
            head_tracking_enabled: true,
            sample_rate,
            surround_virt: None,
            ambisonics_dec: None,
        };
        proc.rebuild_engines();
        proc
    }

    pub fn set_layout(&mut self, layout: AudioChannelLayout) {
        if self.layout != layout {
            self.layout = layout;
            self.rebuild_engines();
        }
    }

    pub fn set_mode(&mut self, mode: SpatialAudioMode) {
        self.mode = mode;
    }

    pub fn set_head_tracking_enabled(&mut self, enabled: bool) {
        self.head_tracking_enabled = enabled;
        if let Some(virt) = &mut self.surround_virt {
            virt.set_head_tracking_enabled(enabled);
        }
    }

    pub fn reset(&mut self) {
        if let Some(virt) = &mut self.surround_virt {
            virt.reset();
        }
        if let Some(dec) = &mut self.ambisonics_dec {
            dec.reset();
        }
    }

    fn rebuild_engines(&mut self) {
        match self.layout {
            AudioChannelLayout::Surround5_1 => {
                let mut v = SurroundVirtualizer::new_5_1(self.sample_rate);
                v.set_head_tracking_enabled(self.head_tracking_enabled);
                self.surround_virt = Some(v);
                self.ambisonics_dec = None;
            }
            AudioChannelLayout::Surround7_1 => {
                let mut v = SurroundVirtualizer::new_7_1(self.sample_rate);
                v.set_head_tracking_enabled(self.head_tracking_enabled);
                self.surround_virt = Some(v);
                self.ambisonics_dec = None;
            }
            AudioChannelLayout::AmbisonicsFoa(ordering, norm) => {
                self.ambisonics_dec = Some(AmbisonicsDecoder::new_foa(ordering, norm));
                self.surround_virt = None;
            }
            _ => {
                self.surround_virt = None;
                self.ambisonics_dec = None;
            }
        }
    }

    /// Processa amostras PCM interleaved multicanal e converte para estéreo binaural `[L, R]`.
    pub fn process(
        &mut self,
        input_interleaved: &[f32],
        head_orientation: Quat,
        output_stereo: &mut Vec<f32>,
    ) {
        let ch_count = self.layout.channels() as usize;
        if ch_count == 0 || input_interleaved.is_empty() {
            output_stereo.clear();
            return;
        }

        let num_frames = input_interleaved.len() / ch_count;
        output_stereo.resize(num_frames * 2, 0.0);

        // Roteamento conforme layout e modo espacial
        match (self.layout, self.mode) {
            // Estéreo ou Mono com DirectStereo / Pass-through
            (AudioChannelLayout::Stereo, _) | (AudioChannelLayout::Mono, _) | (_, SpatialAudioMode::DirectStereo) => {
                if ch_count == 2 {
                    output_stereo[..num_frames * 2].copy_from_slice(&input_interleaved[..num_frames * 2]);
                } else if ch_count == 1 {
                    for frame in 0..num_frames {
                        let sample = input_interleaved[frame];
                        output_stereo[frame * 2] = sample;
                        output_stereo[frame * 2 + 1] = sample;
                    }
                } else {
                    // Fallback downmix simples para multicanal no modo DirectStereo
                    Self::quick_interleaved_downmix(input_interleaved, ch_count, num_frames, output_stereo);
                }
            }

            // Surround 5.1 ou 7.1 Virtualizado
            (AudioChannelLayout::Surround5_1, SpatialAudioMode::VirtualizedBinaural)
            | (AudioChannelLayout::Surround7_1, SpatialAudioMode::VirtualizedBinaural) => {
                if let Some(virt) = &mut self.surround_virt {
                    virt.process_interleaved(input_interleaved, head_orientation, output_stereo);
                } else {
                    Self::quick_interleaved_downmix(input_interleaved, ch_count, num_frames, output_stereo);
                }
            }

            // Ambisonics FOA
            (AudioChannelLayout::AmbisonicsFoa(..), SpatialAudioMode::VirtualizedBinaural) => {
                if let Some(dec) = &mut self.ambisonics_dec {
                    dec.process_interleaved(input_interleaved, head_orientation, output_stereo);
                } else {
                    Self::quick_interleaved_downmix(input_interleaved, ch_count, num_frames, output_stereo);
                }
            }

            // Downmix Simples (ITU-R) ou fallback geral
            _ => {
                Self::quick_interleaved_downmix(input_interleaved, ch_count, num_frames, output_stereo);
            }
        }
    }

    fn quick_interleaved_downmix(
        input: &[f32],
        channels: usize,
        frames: usize,
        out_stereo: &mut [f32],
    ) {
        let norm = (2.0 / channels as f32).min(1.0);
        for f in 0..frames {
            let offset = f * channels;
            let mut left_sum = 0.0f32;
            let mut right_sum = 0.0f32;

            if channels >= 6 {
                // 5.1 / 7.1 mapping aproximado
                let fl = input[offset];
                let fr = input[offset + 1];
                let c = input[offset + 2] * std::f32::consts::FRAC_1_SQRT_2;
                let lfe = input[offset + 3] * 0.5;
                let sl = input[offset + 4] * std::f32::consts::FRAC_1_SQRT_2;
                let sr = input[offset + 5] * std::f32::consts::FRAC_1_SQRT_2;
                left_sum = fl + c + sl + lfe;
                right_sum = fr + c + sr + lfe;
            } else {
                for ch in 0..channels {
                    let s = input[offset + ch];
                    if ch % 2 == 0 {
                        left_sum += s;
                    } else {
                        right_sum += s;
                    }
                }
            }

            out_stereo[f * 2] = left_sum * norm;
            out_stereo[f * 2 + 1] = right_sum * norm;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_processor_lifecycle_and_channel_layouts() {
        let mut proc = SpatialAudioProcessor::new(AudioChannelLayout::Surround5_1, 48000.0);

        let input_6ch = vec![0.1f32; 6 * 128];
        let mut output = Vec::new();

        proc.process(&input_6ch, Quat::IDENTITY, &mut output);
        assert_eq!(output.len(), 128 * 2);

        // Troca para Ambisonics
        proc.set_layout(AudioChannelLayout::AmbisonicsFoa(
            ChannelOrdering::Acn,
            Normalization::Sn3d,
        ));
        let input_4ch = vec![0.2f32; 4 * 64];
        proc.process(&input_4ch, Quat::IDENTITY, &mut output);
        assert_eq!(output.len(), 64 * 2);
    }
}
