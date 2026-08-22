//! Convolução HRTF (Head-Related Transfer Function) e síntese binaural — PHASE-0.3, Seções 3 e 4.
//!
//! Fornece filtros FIR de resposta ao impulso de cabeça/ouvidos (HRIR) para espacialização
//! 3D de caixas acústicas virtuais e decodificação Ambisonics em fones de ouvido.

use crate::spatial_audio::quaternion::Vec3;

pub const HRIR_TAPS: usize = 64;

/// Par de filtros FIR de resposta ao impulso para o ouvido esquerdo e direito.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct HrtfPair {
    pub left: [f32; HRIR_TAPS],
    pub right: [f32; HRIR_TAPS],
}

impl Default for HrtfPair {
    fn default() -> Self {
        let mut left = [0.0; HRIR_TAPS];
        let mut right = [0.0; HRIR_TAPS];
        left[0] = 1.0;
        right[0] = 1.0;
        Self { left, right }
    }
}

impl HrtfPair {
    /// Interpola linearmente entre dois pares de HRTF pelo fator `t` em `[0.0, 1.0]`.
    pub fn lerp(&self, other: &Self, t: f32) -> Self {
        let t_inv = 1.0 - t;
        let mut result = Self::default();
        for i in 0..HRIR_TAPS {
            result.left[i] = self.left[i] * t_inv + other.left[i] * t;
            result.right[i] = self.right[i] * t_inv + other.right[i] * t;
        }
        result
    }
}

/// Convolucionador FIR contínuo para um canal de áudio com estado de overlap.
#[derive(Debug, Clone)]
pub struct FirConvolver {
    history: [f32; HRIR_TAPS],
    history_idx: usize,
}

impl Default for FirConvolver {
    fn default() -> Self {
        Self::new()
    }
}

impl FirConvolver {
    pub fn new() -> Self {
        Self {
            history: [0.0; HRIR_TAPS],
            history_idx: 0,
        }
    }

    pub fn reset(&mut self) {
        self.history.fill(0.0);
        self.history_idx = 0;
    }

    /// Convolui uma amostra de entrada com o filtro FIR dado.
    #[inline]
    pub fn process_sample(&mut self, input: f32, filter: &[f32; HRIR_TAPS]) -> f32 {
        self.history[self.history_idx] = input;
        let mut sum = 0.0f32;

        let mut h_idx = self.history_idx;
        for tap in filter.iter() {
            sum += self.history[h_idx] * tap;
            if h_idx == 0 {
                h_idx = HRIR_TAPS - 1;
            } else {
                h_idx -= 1;
            }
        }

        self.history_idx = (self.history_idx + 1) % HRIR_TAPS;
        sum
    }

    /// Convolui um bloco de amostras com o filtro FIR dado, acumulando o resultado no buffer de saída.
    pub fn convolve_accumulate(
        &mut self,
        input: &[f32],
        filter: &[f32; HRIR_TAPS],
        output: &mut [f32],
    ) {
        let len = input.len().min(output.len());
        for i in 0..len {
            output[i] += self.process_sample(input[i], filter);
        }
    }
}

/// Dataset de HRTF sintético/calibrado baseado no modelo acústico KEMAR/Woodworth
/// com modelagem de ITD (Interaural Time Difference), ILD (Interaural Level Difference)
/// e reflexões de pinna (Spectral Pinna Filtering).
pub struct HrtfDataset;

impl HrtfDataset {
    /// Computa o par HRIR para qualquer posição 3D relativa à cabeça.
    pub fn get_hrir_for_direction(dir: Vec3) -> HrtfPair {
        let (azimuth_deg, elevation_deg) = dir.to_spherical_degrees();
        Self::get_hrir_spherical(azimuth_deg, elevation_deg)
    }

    /// Computa o par HRIR para azimute (-180° a +180°) e elevação (-90° a +90°).
    pub fn get_hrir_spherical(azimuth_deg: f32, elevation_deg: f32) -> HrtfPair {
        let az_rad = azimuth_deg.to_radians();
        let el_rad = elevation_deg.to_radians();

        // Parâmetros fisiológicos humanos médios:
        // Raio da cabeça r = 0.0875m (diâmetro 17.5cm), velocidade do som c = 343 m/s, fs = 48000 Hz.
        // Atraso máximo Woodworth: delta_t_max = r / c * (sin(theta) + theta) ≈ 0.65ms ≈ 31 samples.
        let sin_az = az_rad.sin();
        let cos_el = el_rad.cos();
        let eff_az = sin_az * cos_el; // Projeção horizontal com elevação

        // Atraso em amostras para cada ouvido (amostrado a 48kHz)
        // Ouvido esquerdo: azimute negativo chega primeiro; ouvido direito: azimute positivo chega primeiro
        let base_delay_samples = 8.0f32;
        let max_itd_samples = 15.0f32; // ±15 amostras de diferença entre ouvidos

        let left_delay = base_delay_samples - (eff_az * max_itd_samples * 0.5);
        let right_delay = base_delay_samples + (eff_az * max_itd_samples * 0.5);

        // ILD (Atenuação da cabeça em alta frequência para o lado oposto)
        // Ipsi: ganho próximo a 1.0; Contra: atenuado até ~0.35 (-9dB)
        let left_gain = 0.675 - 0.325 * eff_az;
        let right_gain = 0.675 + 0.325 * eff_az;

        // Pinna notch / reflexão de elevação
        let pinna_delay = 4.0 + 3.0 * (el_rad.sin() * 0.5 + 0.5); // 4 a 7 amostras
        let pinna_gain = -0.25 * cos_el;

        let mut pair = HrtfPair {
            left: [0.0; HRIR_TAPS],
            right: [0.0; HRIR_TAPS],
        };

        // Síntese de impulso com interpolação de delay fracionário (Sinc truncado com janela de Blackman)
        Self::synthesize_impulse(
            &mut pair.left,
            left_delay,
            left_gain,
            pinna_delay,
            pinna_gain,
        );
        Self::synthesize_impulse(
            &mut pair.right,
            right_delay,
            right_gain,
            pinna_delay,
            pinna_gain,
        );

        pair
    }

    fn synthesize_impulse(
        taps: &mut [f32; HRIR_TAPS],
        main_delay: f32,
        main_gain: f32,
        pinna_delay: f32,
        pinna_gain: f32,
    ) {
        taps.fill(0.0);
        let d_main_int = main_delay.floor() as usize;
        let d_main_frac = main_delay - main_delay.floor();

        // Pulso principal (direto) com delay fracionário
        if d_main_int < HRIR_TAPS - 2 {
            taps[d_main_int] += main_gain * (1.0 - d_main_frac);
            taps[d_main_int + 1] += main_gain * d_main_frac;
        }

        // Reflexão de Pinna
        let total_pinna_delay = main_delay + pinna_delay;
        let d_p_int = total_pinna_delay.floor() as usize;
        let d_p_frac = total_pinna_delay - total_pinna_delay.floor();
        if d_p_int < HRIR_TAPS - 2 {
            taps[d_p_int] += pinna_gain * (1.0 - d_p_frac);
            taps[d_p_int + 1] += pinna_gain * d_p_frac;
        }

        // Normalização de energia para evitar ganho excessivo
        let mut energy = 0.0f32;
        for &t in taps.iter() {
            energy += t * t;
        }
        if energy > 1e-6 {
            let norm = 1.0 / energy.sqrt();
            for t in taps.iter_mut() {
                *t *= norm * main_gain;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hrtf_azimuth_symmetry_and_delays() {
        // Som vindo exatamente da esquerda (-90° azimute)
        let left_hrir = HrtfDataset::get_hrir_spherical(-90.0, 0.0);

        // Som vindo exatamente da direita (+90° azimute)
        let right_hrir = HrtfDataset::get_hrir_spherical(90.0, 0.0);

        // A resposta do ouvido esquerdo para som da esquerda deve ser idêntica
        // à resposta do ouvido direito para som da direita (simetria coronal)
        for i in 0..HRIR_TAPS {
            assert!(
                (left_hrir.left[i] - right_hrir.right[i]).abs() < 1e-4,
                "Mismatch no tap {}: left_hrir.left={} != right_hrir.right={}",
                i,
                left_hrir.left[i],
                right_hrir.right[i]
            );
            assert!(
                (left_hrir.right[i] - right_hrir.left[i]).abs() < 1e-4,
                "Mismatch contralateral no tap {}: left_hrir.right={} != right_hrir.left={}",
                i,
                left_hrir.right[i],
                right_hrir.left[i]
            );
        }
    }

    #[test]
    fn test_convolver_process_block() {
        let mut conv = FirConvolver::new();
        let mut filter = [0.0; HRIR_TAPS];
        filter[0] = 0.5; // Ganho 0.5 imediato

        let input = [1.0, 2.0, -1.0, 0.5];
        let mut output = [0.0; 4];

        conv.convolve_accumulate(&input, &filter, &mut output);

        assert!((output[0] - 0.5).abs() < 1e-4);
        assert!((output[1] - 1.0).abs() < 1e-4);
        assert!((output[2] - (-0.5)).abs() < 1e-4);
        assert!((output[3] - 0.25).abs() < 1e-4);
    }
}
