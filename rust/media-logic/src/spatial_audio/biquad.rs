//! Filtros IIR Biquad (Butterworth de 2ª ordem) — PHASE-0.3, Seção 4 (LFE).
//!
//! Utilizado no processamento do canal LFE (subwoofer 5.1/7.1) para isolar
//! as frequências sub-graves (<120Hz) e mixar nos canais frontais com
//! resposta de fase suave, sem instabilidade numérica.

#[derive(Debug, Clone, Copy)]
pub struct BiquadFilter {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    // Estados internos da forma direta II transposta (DF2T)
    z1: f32,
    z2: f32,
}

impl BiquadFilter {
    pub const fn new(b0: f32, b1: f32, b2: f32, a1: f32, a2: f32) -> Self {
        Self {
            b0,
            b1,
            b2,
            a1,
            a2,
            z1: 0.0,
            z2: 0.0,
        }
    }

    /// Cria um filtro Low-Pass Butterworth de 2ª ordem (Q = 1/√2 ≈ 0.7071).
    ///
    /// Exemplo: `cutoff_hz = 120.0`, `sample_rate = 48000.0` para o canal LFE.
    pub fn lowpass_butterworth(cutoff_hz: f32, sample_rate: f32) -> Self {
        let w0 = 2.0 * std::f32::consts::PI * (cutoff_hz / sample_rate);
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        let alpha = sin_w0 / (2.0 * std::f32::consts::FRAC_1_SQRT_2); // Q = 0.7071

        let a0 = 1.0 + alpha;
        let b0 = ((1.0 - cos_w0) * 0.5) / a0;
        let b1 = (1.0 - cos_w0) / a0;
        let b2 = ((1.0 - cos_w0) * 0.5) / a0;
        let a1 = (-2.0 * cos_w0) / a0;
        let a2 = (1.0 - alpha) / a0;

        Self::new(b0, b1, b2, a1, a2)
    }

    /// Processa uma única amostra de áudio (Direct Form II Transposed).
    #[inline]
    pub fn process_sample(&mut self, input: f32) -> f32 {
        let output = self.b0 * input + self.z1;
        self.z1 = self.b1 * input - self.a1 * output + self.z2;
        self.z2 = self.b2 * input - self.a2 * output;
        output
    }

    /// Processa um bloco de amostras in-place.
    pub fn process_block(&mut self, samples: &mut [f32]) {
        for s in samples.iter_mut() {
            *s = self.process_sample(*s);
        }
    }

    /// Reseta o histórico de atraso do filtro.
    pub fn reset(&mut self) {
        self.z1 = 0.0;
        self.z2 = 0.0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_butterworth_lowpass_attenuates_high_frequencies() {
        let sample_rate = 48000.0;
        let cutoff = 120.0;
        let mut filter = BiquadFilter::lowpass_butterworth(cutoff, sample_rate);

        // 1. Onda senoidal de 50 Hz (pass-band)
        let num_samples = 4800; // 100ms
        let mut pass_energy = 0.0f32;
        for i in 0..num_samples {
            let t = i as f32 / sample_rate;
            let sample = (2.0 * std::f32::consts::PI * 50.0 * t).sin();
            let filtered = filter.process_sample(sample);
            // Ignora o transiente inicial (primeiras 500 amostras)
            if i > 500 {
                pass_energy += filtered * filtered;
            }
        }

        // 2. Onda senoidal de 2000 Hz (stop-band)
        filter.reset();
        let mut stop_energy = 0.0f32;
        for i in 0..num_samples {
            let t = i as f32 / sample_rate;
            let sample = (2.0 * std::f32::consts::PI * 2000.0 * t).sin();
            let filtered = filter.process_sample(sample);
            if i > 500 {
                stop_energy += filtered * filtered;
            }
        }

        // A frequência de 2kHz deve ser dramaticamente atenuada em relação a 50Hz (> 40dB de diferença)
        assert!(pass_energy > 100.0);
        assert!(stop_energy < pass_energy * 0.001);
    }
}
