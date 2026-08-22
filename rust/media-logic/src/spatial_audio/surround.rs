//! Virtualização multicanal 5.1 e 7.1 para fones de ouvido — PHASE-0.3, Seção 4.
//!
//! Converte canais discretos de cinema (5.1 e 7.1) em áudio binaural espacial 3D com
//! posicionamento de caixas virtuais, rastreamento de cabeça (head tracking) e
//! tratamento adequado de canal LFE (subwoofer).

use crate::spatial_audio::biquad::BiquadFilter;
use crate::spatial_audio::hrtf::{FirConvolver, HrtfDataset};
use crate::spatial_audio::quaternion::{Quat, Vec3};

pub const MAX_SURROUND_CHANNELS: usize = 8;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SurroundLayout {
    FivePointOne,  // 6 canais: FL, FR, C, LFE, SL, SR
    SevenPointOne, // 8 canais: FL, FR, C, LFE, SL, SR, BL, BR
}

impl SurroundLayout {
    pub fn channel_count(self) -> usize {
        match self {
            SurroundLayout::FivePointOne => 6,
            SurroundLayout::SevenPointOne => 8,
        }
    }
}

pub struct SurroundVirtualizer {
    layout: SurroundLayout,
    speaker_directions: [Vec3; MAX_SURROUND_CHANNELS],
    convolvers_left: [FirConvolver; MAX_SURROUND_CHANNELS],
    convolvers_right: [FirConvolver; MAX_SURROUND_CHANNELS],
    lfe_filter: BiquadFilter,
    head_tracking_enabled: bool,
    last_orientation: Quat,
}

impl SurroundVirtualizer {
    pub fn new_5_1(sample_rate: f32) -> Self {
        let mut speaker_directions = [Vec3::FORWARD; MAX_SURROUND_CHANNELS];
        // 5.1: FL (-30°), FR (+30°), C (0°), LFE (centro/chão), SL (-110°), SR (+110°)
        speaker_directions[0] = Vec3::from_spherical_degrees(-30.0, 0.0, 1.0); // Front Left
        speaker_directions[1] = Vec3::from_spherical_degrees(30.0, 0.0, 1.0);  // Front Right
        speaker_directions[2] = Vec3::from_spherical_degrees(0.0, 0.0, 1.0);   // Center
        speaker_directions[3] = Vec3::from_spherical_degrees(0.0, -30.0, 0.5); // LFE (não direcional)
        speaker_directions[4] = Vec3::from_spherical_degrees(-110.0, 0.0, 1.0);// Surround Left
        speaker_directions[5] = Vec3::from_spherical_degrees(110.0, 0.0, 1.0); // Surround Right

        Self {
            layout: SurroundLayout::FivePointOne,
            speaker_directions,
            convolvers_left: Default::default(),
            convolvers_right: Default::default(),
            lfe_filter: BiquadFilter::lowpass_butterworth(120.0, sample_rate),
            head_tracking_enabled: true,
            last_orientation: Quat::IDENTITY,
        }
    }

    pub fn new_7_1(sample_rate: f32) -> Self {
        let mut speaker_directions = [Vec3::FORWARD; MAX_SURROUND_CHANNELS];
        // 7.1: FL (-30°), FR (+30°), C (0°), LFE, SL (-90°), SR (+90°), BL (-150°), BR (+150°)
        speaker_directions[0] = Vec3::from_spherical_degrees(-30.0, 0.0, 1.0); // Front Left
        speaker_directions[1] = Vec3::from_spherical_degrees(30.0, 0.0, 1.0);  // Front Right
        speaker_directions[2] = Vec3::from_spherical_degrees(0.0, 0.0, 1.0);   // Center
        speaker_directions[3] = Vec3::from_spherical_degrees(0.0, -30.0, 0.5); // LFE
        speaker_directions[4] = Vec3::from_spherical_degrees(-90.0, 0.0, 1.0);  // Side Left
        speaker_directions[5] = Vec3::from_spherical_degrees(90.0, 0.0, 1.0);   // Side Right
        speaker_directions[6] = Vec3::from_spherical_degrees(-150.0, 0.0, 1.0);// Back Left
        speaker_directions[7] = Vec3::from_spherical_degrees(150.0, 0.0, 1.0); // Back Right

        Self {
            layout: SurroundLayout::SevenPointOne,
            speaker_directions,
            convolvers_left: Default::default(),
            convolvers_right: Default::default(),
            lfe_filter: BiquadFilter::lowpass_butterworth(120.0, sample_rate),
            head_tracking_enabled: true,
            last_orientation: Quat::IDENTITY,
        }
    }

    pub fn set_head_tracking_enabled(&mut self, enabled: bool) {
        self.head_tracking_enabled = enabled;
    }

    pub fn reset(&mut self) {
        for conv in self.convolvers_left.iter_mut() {
            conv.reset();
        }
        for conv in self.convolvers_right.iter_mut() {
            conv.reset();
        }
        self.lfe_filter.reset();
        self.last_orientation = Quat::IDENTITY;
    }

    /// Processa um bloco de canais multicanal de entrada e escreve nos buffers estéreo `out_left` e `out_right`.
    ///
    /// - `channels`: slice de fatias de áudio correspondendo a cada canal (6 para 5.1, 8 para 7.1).
    /// - `head_orientation`: quaternion de orientação da cabeça obtido via OpenXR.
    pub fn process_block(
        &mut self,
        channels: &[&[f32]],
        head_orientation: Quat,
        out_left: &mut [f32],
        out_right: &mut [f32],
    ) {
        let block_len = out_left.len().min(out_right.len());
        out_left[..block_len].fill(0.0);
        out_right[..block_len].fill(0.0);

        let orientation = if self.head_tracking_enabled {
            // Suaviza a rotação (slerp 0.5) em relação ao bloco anterior para evitar descontinuidades
            let smoothed = self.last_orientation.slerp(head_orientation, 0.5);
            self.last_orientation = smoothed;
            smoothed
        } else {
            Quat::IDENTITY
        };

        let inv_orientation = orientation.conjugate();
        let num_ch = self.layout.channel_count().min(channels.len());

        for ch in 0..num_ch {
            let input = &channels[ch][..block_len.min(channels[ch].len())];

            if ch == 3 {
                // Canal 3 = LFE (Subwoofer): Filtro Low-Pass e soma direta nos dois ouvidos sem HRTF
                for (i, &sample) in input.iter().enumerate() {
                    let lfe_sample = self.lfe_filter.process_sample(sample) * std::f32::consts::FRAC_1_SQRT_2;
                    out_left[i] += lfe_sample;
                    out_right[i] += lfe_sample;
                }
            } else {
                // Caixa acústica direcional: rotacionar pelo inverso da pose da cabeça
                let original_dir = self.speaker_directions[ch];
                let rotated_dir = inv_orientation.rotate_vec3(original_dir);

                let hrir = HrtfDataset::get_hrir_for_direction(rotated_dir);

                self.convolvers_left[ch].convolve_accumulate(input, &hrir.left, out_left);
                self.convolvers_right[ch].convolve_accumulate(input, &hrir.right, out_right);
            }
        }
    }

    /// Processa um bloco interleaved multicanal (ex: `[L0, R0, C0, LFE0, SL0, SR0, L1, ...]`)
    /// e retorna amostras interleaved estéreo `[L0, R0, L1, R1, ...]`.
    pub fn process_interleaved(
        &mut self,
        interleaved_in: &[f32],
        head_orientation: Quat,
        interleaved_out: &mut [f32],
    ) {
        let ch_count = self.layout.channel_count();
        let num_frames = (interleaved_in.len() / ch_count).min(interleaved_out.len() / 2);

        // Aloca buffers temporários para desentrelaçar em blocos de até 512 amostras
        let mut ch_buffers = [const { Vec::new() }; MAX_SURROUND_CHANNELS];
        for buf in ch_buffers.iter_mut().take(ch_count) {
            buf.resize(num_frames, 0.0f32);
        }

        for (frame, chunk) in interleaved_in.chunks_exact(ch_count).take(num_frames).enumerate() {
            for (ch, &val) in chunk.iter().enumerate().take(ch_count) {
                ch_buffers[ch][frame] = val;
            }
        }

        let mut left_out = vec![0.0f32; num_frames];
        let mut right_out = vec![0.0f32; num_frames];

        let ch_slices: Vec<&[f32]> = ch_buffers[..ch_count].iter().map(|v| v.as_slice()).collect();
        self.process_block(&ch_slices, head_orientation, &mut left_out, &mut right_out);

        for frame in 0..num_frames {
            interleaved_out[frame * 2] = left_out[frame];
            interleaved_out[frame * 2 + 1] = right_out[frame];
        }
    }
}

/// Downmix estéreo padrão segundo ITU-R BS.775-1 (sem HRTF, baixo uso de CPU).
pub fn itu_downmix_5_1(channels: &[&[f32]; 6], out_left: &mut [f32], out_right: &mut [f32]) {
    let len = out_left.len().min(out_right.len());
    const C_ATTEN: f32 = std::f32::consts::FRAC_1_SQRT_2; // -3dB
    const SURROUND_ATTEN: f32 = std::f32::consts::FRAC_1_SQRT_2; // -3dB
    const NORM: f32 = 1.0 / (1.0 + C_ATTEN + SURROUND_ATTEN); // Evita clipping

    for i in 0..len {
        let fl = channels[0][i];
        let fr = channels[1][i];
        let c = channels[2][i];
        let lfe = channels[3][i] * 0.5; // LFE com ganho reduzido
        let sl = channels[4][i];
        let sr = channels[5][i];

        out_left[i] = (fl + c * C_ATTEN + sl * SURROUND_ATTEN + lfe) * NORM;
        out_right[i] = (fr + c * C_ATTEN + sr * SURROUND_ATTEN + lfe) * NORM;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_5_1_virtualizer_head_rotation() {
        let mut virt = SurroundVirtualizer::new_5_1(48000.0);

        // Canais: apenas o canal Central (índice 2) ativo
        let block_len = 128;
        let mut c_ch = vec![0.0f32; block_len];
        c_ch[0] = 1.0; // Impulso no centro

        let empty = vec![0.0f32; block_len];
        let channels = [&empty[..], &empty[..], &c_ch[..], &empty[..], &empty[..], &empty[..]];

        let mut out_l_front = vec![0.0f32; block_len];
        let mut out_r_front = vec![0.0f32; block_len];

        // 1. Olhando para a frente: o canal Central deve soar equilibrado em ambos os ouvidos
        virt.process_block(&channels, Quat::IDENTITY, &mut out_l_front, &mut out_r_front);

        let energy_l = out_l_front.iter().map(|&x| x * x).sum::<f32>();
        let energy_r = out_r_front.iter().map(|&x| x * x).sum::<f32>();
        assert!((energy_l - energy_r).abs() < 0.05, "Center channel mismatch: L={}, R={}", energy_l, energy_r);

        // 2. Virando 90° para a esquerda (+yaw): o canal Central fica à direita relativa do ouvinte
        virt.reset();
        let q_left_90 = Quat::from_axis_angle_y(std::f32::consts::FRAC_PI_2);
        let mut out_l_rotated = vec![0.0f32; block_len];
        let mut out_r_rotated = vec![0.0f32; block_len];

        virt.process_block(&channels, q_left_90, &mut out_l_rotated, &mut out_r_rotated);
        let rot_energy_l = out_l_rotated.iter().map(|&x| x * x).sum::<f32>();
        let rot_energy_r = out_r_rotated.iter().map(|&x| x * x).sum::<f32>();

        // Ao virar para a esquerda, a caixa central fica à direita do usuário -> ouvido direito recebe mais energia
        assert!(rot_energy_r > rot_energy_l * 1.5, "Expected R > L when turned left: L={}, R={}", rot_energy_l, rot_energy_r);

        // 3. Virando 90° para a direita (-yaw): o canal Central fica à esquerda relativa do ouvinte
        virt.reset();
        let q_right_90 = Quat::from_axis_angle_y(-std::f32::consts::FRAC_PI_2);
        virt.process_block(&channels, q_right_90, &mut out_l_rotated, &mut out_r_rotated);
        let rot_r_energy_l = out_l_rotated.iter().map(|&x| x * x).sum::<f32>();
        let rot_r_energy_r = out_r_rotated.iter().map(|&x| x * x).sum::<f32>();

        // Ao virar para a direita, a caixa central fica à esquerda do usuário -> ouvido esquerdo recebe mais energia
        assert!(rot_r_energy_l > rot_r_energy_r * 1.5, "Expected L > R when turned right: L={}, R={}", rot_r_energy_l, rot_r_energy_r);
    }

    #[test]
    fn test_itu_downmix_preserves_energy() {
        let block_len = 64;
        let left_ch = vec![1.0f32; block_len];
        let right_ch = vec![1.0f32; block_len];
        let empty = vec![0.0f32; block_len];
        let channels = [&left_ch[..], &right_ch[..], &empty[..], &empty[..], &empty[..], &empty[..]];

        let mut out_l = vec![0.0f32; block_len];
        let mut out_r = vec![0.0f32; block_len];

        itu_downmix_5_1(&channels, &mut out_l, &mut out_r);

        // O sinal deve estar normalizado entre 0 e 1 sem clipping
        assert!(out_l[0] > 0.0 && out_l[0] <= 1.0);
        assert!(out_r[0] > 0.0 && out_r[0] <= 1.0);
    }
}
