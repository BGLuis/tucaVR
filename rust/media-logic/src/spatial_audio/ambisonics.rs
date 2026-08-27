//! Decodificador Ambisonics B-format (1ª Ordem FOA / 2ª Ordem SOA) — PHASE-0.3, Seção 3.
//!
//! Converte campos sonoros esféricos Ambisonics (comuns em vídeos 360° do YouTube e VR180)
//! em áudio binaural estéreo imersivo com rotação tridimensional por rastreamento de cabeça.

use crate::spatial_audio::hrtf::{FirConvolver, HrtfDataset};
use crate::spatial_audio::quaternion::{Quat, Vec3};

pub const AMBI_FOA_CHANNELS: usize = 4;
pub const VIRTUAL_DOME_SPEAKERS: usize = 8;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChannelOrdering {
    Acn,  // Padrão moderno (YouTube / AmbiX): 0=W, 1=Y, 2=Z, 3=X
    FuMa, // Formato clássico: 0=W, 1=X, 2=Y, 3=Z
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Normalization {
    Sn3d, // Padrão AmbiX / YouTube
    N3d,
    FuMa,
}

pub struct AmbisonicsDecoder {
    ordering: ChannelOrdering,
    normalization: Normalization,
    speaker_directions: [Vec3; VIRTUAL_DOME_SPEAKERS],
    convolvers_left: [FirConvolver; VIRTUAL_DOME_SPEAKERS],
    convolvers_right: [FirConvolver; VIRTUAL_DOME_SPEAKERS],
    last_orientation: Quat,
}

impl AmbisonicsDecoder {
    pub fn new_foa(ordering: ChannelOrdering, normalization: Normalization) -> Self {
        // Grid virtual regular de 8 caixas acústicas (vértices de um cubo regular):
        // Azimutes: ±45°, ±135°; Elevações: ±35.26° (atan(1/√2))
        let el = 35.264f32;
        let mut speaker_directions = [Vec3::FORWARD; VIRTUAL_DOME_SPEAKERS];

        speaker_directions[0] = Vec3::from_spherical_degrees(45.0, el, 1.0);    // Top Front Right
        speaker_directions[1] = Vec3::from_spherical_degrees(-45.0, el, 1.0);   // Top Front Left
        speaker_directions[2] = Vec3::from_spherical_degrees(135.0, el, 1.0);   // Top Back Right
        speaker_directions[3] = Vec3::from_spherical_degrees(-135.0, el, 1.0);  // Top Back Left
        speaker_directions[4] = Vec3::from_spherical_degrees(45.0, -el, 1.0);   // Bottom Front Right
        speaker_directions[5] = Vec3::from_spherical_degrees(-45.0, -el, 1.0);  // Bottom Front Left
        speaker_directions[6] = Vec3::from_spherical_degrees(135.0, -el, 1.0);  // Bottom Back Right
        speaker_directions[7] = Vec3::from_spherical_degrees(-135.0, -el, 1.0); // Bottom Back Left

        Self {
            ordering,
            normalization,
            speaker_directions,
            convolvers_left: Default::default(),
            convolvers_right: Default::default(),
            last_orientation: Quat::IDENTITY,
        }
    }

    pub fn reset(&mut self) {
        for conv in self.convolvers_left.iter_mut() {
            conv.reset();
        }
        for conv in self.convolvers_right.iter_mut() {
            conv.reset();
        }
        self.last_orientation = Quat::IDENTITY;
    }

    /// Processa um bloco de canais Ambisonics FOA (4 canais: W, Y, Z, X em ACN/SN3D).
    #[allow(clippy::needless_range_loop)]
    pub fn process_block(
        &mut self,
        channels: &[&[f32]],
        head_orientation: Quat,
        out_left: &mut [f32],
        out_right: &mut [f32],
    ) {
        if channels.len() < AMBI_FOA_CHANNELS {
            out_left.fill(0.0);
            out_right.fill(0.0);
            return;
        }

        let block_len = out_left.len().min(out_right.len());
        out_left[..block_len].fill(0.0);
        out_right[..block_len].fill(0.0);

        // Suavização da orientação da cabeça
        let smoothed_orientation = self.last_orientation.slerp(head_orientation, 0.5);
        self.last_orientation = smoothed_orientation;
        let inv_orientation = smoothed_orientation.conjugate();

        // 1. Extração dos canais brutos com normalização para ACN / SN3D
        let (ch_w, ch_y, ch_z, ch_x) = match self.ordering {
            ChannelOrdering::Acn => (channels[0], channels[1], channels[2], channels[3]),
            ChannelOrdering::FuMa => (channels[0], channels[2], channels[3], channels[1]),
        };

        // Fator de escala se for FuMa
        let w_scale = if self.normalization == Normalization::FuMa {
            std::f32::consts::SQRT_2
        } else {
            1.0
        };

        // 2. Rotação de harmônicos esféricos no domínio B-format e decodificação para o grid de 8 caixas
        let sqrt3 = 3.0f32.sqrt();
        let inv_8 = 1.0f32 / (VIRTUAL_DOME_SPEAKERS as f32);

        let mut speaker_buffers = [const { Vec::new() }; VIRTUAL_DOME_SPEAKERS];
        for buf in speaker_buffers.iter_mut().take(VIRTUAL_DOME_SPEAKERS) {
            buf.resize(block_len, 0.0f32);
        }

        for i in 0..block_len {
            let w = ch_w.get(i).copied().unwrap_or(0.0) * w_scale;
            let y_raw = ch_y.get(i).copied().unwrap_or(0.0);
            let z_raw = ch_z.get(i).copied().unwrap_or(0.0);
            let x_raw = ch_x.get(i).copied().unwrap_or(0.0);

            // Vetor direcional tridimensional no sistema de coordenadas do vídeo:
            // X_ambi = Front (-Z no OpenXR), Y_ambi = Right (+X no OpenXR), Z_ambi = Up (+Y no OpenXR)
            let raw_dir = Vec3::new(y_raw, z_raw, -x_raw);
            let rot_dir = inv_orientation.rotate_vec3(raw_dir);

            let rot_y = rot_dir.x;
            let rot_z = rot_dir.y;
            let rot_x = -rot_dir.z;

            // Decodificação para cada uma das 8 caixas virtuais
            for (sp_idx, sp_dir) in self.speaker_directions.iter().enumerate() {
                // Direção da caixa virtual em coordenadas esféricas Ambisonics
                let sp_ambi_x = -sp_dir.z; // Frente
                let sp_ambi_y = sp_dir.x;  // Direita
                let sp_ambi_z = sp_dir.y;  // Cima

                let gain = w + sqrt3 * (rot_x * sp_ambi_x + rot_y * sp_ambi_y + rot_z * sp_ambi_z);
                speaker_buffers[sp_idx][i] = gain * inv_8;
            }
        }

        // 3. Convolução binaural de cada caixa virtual com HRTF
        for (sp, buf) in speaker_buffers.iter().enumerate().take(VIRTUAL_DOME_SPEAKERS) {
            let sp_dir = self.speaker_directions[sp];
            let hrir = HrtfDataset::get_hrir_for_direction(sp_dir);

            self.convolvers_left[sp].convolve_accumulate(buf, &hrir.left, out_left);
            self.convolvers_right[sp].convolve_accumulate(buf, &hrir.right, out_right);
        }
    }

    /// Processa bloco interleaved Ambisonics FOA `[W0, Y0, Z0, X0, W1, ...]`.
    pub fn process_interleaved(
        &mut self,
        interleaved_in: &[f32],
        head_orientation: Quat,
        interleaved_out: &mut [f32],
    ) {
        let num_frames = (interleaved_in.len() / AMBI_FOA_CHANNELS).min(interleaved_out.len() / 2);

        let mut ch0 = vec![0.0f32; num_frames];
        let mut ch1 = vec![0.0f32; num_frames];
        let mut ch2 = vec![0.0f32; num_frames];
        let mut ch3 = vec![0.0f32; num_frames];

        for frame in 0..num_frames {
            let offset = frame * AMBI_FOA_CHANNELS;
            ch0[frame] = interleaved_in[offset];
            ch1[frame] = interleaved_in[offset + 1];
            ch2[frame] = interleaved_in[offset + 2];
            ch3[frame] = interleaved_in[offset + 3];
        }

        let mut left_out = vec![0.0f32; num_frames];
        let mut right_out = vec![0.0f32; num_frames];

        let ch_slices = [&ch0[..], &ch1[..], &ch2[..], &ch3[..]];
        self.process_block(&ch_slices, head_orientation, &mut left_out, &mut right_out);

        for frame in 0..num_frames {
            interleaved_out[frame * 2] = left_out[frame];
            interleaved_out[frame * 2 + 1] = right_out[frame];
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ambisonics_rotation_field() {
        let mut decoder = AmbisonicsDecoder::new_foa(ChannelOrdering::Acn, Normalization::Sn3d);

        let block_len = 128;
        // Som puramente frontal: X = 1.0, W = 1.0/√3 ≈ 0.577, Y = 0, Z = 0
        let ch_w = vec![0.577f32; block_len];
        let ch_y = vec![0.0f32; block_len];
        let ch_z = vec![0.0f32; block_len];
        let ch_x = vec![1.0f32; block_len];

        let channels = [&ch_w[..], &ch_y[..], &ch_z[..], &ch_x[..]];

        let mut out_l = vec![0.0f32; block_len];
        let mut out_r = vec![0.0f32; block_len];

        // 1. Olhando para a frente: energia simétrica
        decoder.process_block(&channels, Quat::IDENTITY, &mut out_l, &mut out_r);
        let energy_l = out_l.iter().map(|&x| x * x).sum::<f32>();
        let energy_r = out_r.iter().map(|&x| x * x).sum::<f32>();
        assert!((energy_l - energy_r).abs() < 0.05);

        // 2. Virando 90° para a esquerda (+yaw): som frontal gira para a direita relativa da cabeça
        decoder.reset();
        let q_left_90 = Quat::from_axis_angle_y(std::f32::consts::FRAC_PI_2);
        let mut out_l_rot = vec![0.0f32; block_len];
        let mut out_r_rot = vec![0.0f32; block_len];
        decoder.process_block(&channels, q_left_90, &mut out_l_rot, &mut out_r_rot);

        let rot_energy_l = out_l_rot.iter().map(|&x| x * x).sum::<f32>();
        let rot_energy_r = out_r_rot.iter().map(|&x| x * x).sum::<f32>();
        assert!(rot_energy_r > rot_energy_l * 1.5, "Esperava som na direita ao virar para a esquerda: L={}, R={}", rot_energy_l, rot_energy_r);

        // 3. Virando 90° para a direita (-yaw): som frontal gira para a esquerda relativa da cabeça
        decoder.reset();
        let q_right_90 = Quat::from_axis_angle_y(-std::f32::consts::FRAC_PI_2);
        decoder.process_block(&channels, q_right_90, &mut out_l_rot, &mut out_r_rot);
        let rot_r_energy_l = out_l_rot.iter().map(|&x| x * x).sum::<f32>();
        let rot_r_energy_r = out_r_rot.iter().map(|&x| x * x).sum::<f32>();
        assert!(rot_r_energy_l > rot_r_energy_r * 1.5, "Esperava som na esquerda ao virar para a direita: L={}, R={}", rot_r_energy_l, rot_r_energy_r);
    }
}
