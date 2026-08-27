//! Algoritmo de Seleção Adaptativa de Bitrate (ABR) para HLS (T8.4).

use super::playlist::HlsVariant;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct AdaptiveBitrateManager {
    variants: Vec<HlsVariant>,
    current_index: usize,
    manual_override: Option<usize>,
    consecutive_slow_count: usize,
    consecutive_fast_count: usize,
    hysteresis_threshold: usize,
    last_measured_bandwidth: u64, // bits/s
}

impl AdaptiveBitrateManager {
    pub fn new(variants: Vec<HlsVariant>) -> Self {
        let initial_index = if variants.len() > 1 {
            // Inicia na variante do meio ou 720p para começar rápido sem estourar banda
            variants.len() / 2
        } else {
            0
        };

        Self {
            variants,
            current_index: initial_index,
            manual_override: None,
            consecutive_slow_count: 0,
            consecutive_fast_count: 0,
            hysteresis_threshold: 3,
            last_measured_bandwidth: 0,
        }
    }

    pub fn variants(&self) -> &[HlsVariant] {
        &self.variants
    }

    pub fn current_variant_index(&self) -> usize {
        self.manual_override.unwrap_or(self.current_index)
    }

    pub fn current_variant(&self) -> Option<&HlsVariant> {
        let idx = self.current_variant_index();
        self.variants.get(idx)
    }

    pub fn set_manual_override(&mut self, variant_index: Option<usize>) {
        if let Some(idx) = variant_index {
            if idx < self.variants.len() {
                self.manual_override = Some(idx);
            }
        } else {
            self.manual_override = None;
        }
    }

    pub fn is_auto(&self) -> bool {
        self.manual_override.is_none()
    }

    pub fn last_measured_bandwidth(&self) -> u64 {
        self.last_measured_bandwidth
    }

    /// Registra o download de um segmento e atualiza a decisão de ABR.
    /// Retorna `true` se houve troca de qualidade recomendada.
    pub fn record_segment_download(
        &mut self,
        bytes_downloaded: usize,
        download_duration: Duration,
        segment_duration_sec: f64,
    ) -> bool {
        let secs = download_duration.as_secs_f64().max(0.001);
        let measured_bps = ((bytes_downloaded as f64 * 8.0) / secs) as u64;
        self.last_measured_bandwidth = measured_bps;

        // Se o usuário travou uma qualidade manual, não altera a seleção automática
        if self.manual_override.is_some() || self.variants.len() <= 1 {
            return false;
        }

        if segment_duration_sec <= 0.0 {
            return false;
        }

        let ratio = secs / segment_duration_sec;

        // Condição de redução: download levou mais de 80% da duração do segmento
        if ratio > 0.8 {
            self.consecutive_slow_count += 1;
            self.consecutive_fast_count = 0;

            if self.consecutive_slow_count >= self.hysteresis_threshold && self.current_index > 0 {
                self.current_index -= 1;
                self.consecutive_slow_count = 0;
                log::info!(
                    "HLS ABR: Reduzindo qualidade para variante {} (banda estimada: {} kbps)",
                    self.current_index,
                    measured_bps / 1000
                );
                return true;
            }
        }
        // Condição de aumento: download levou menos de 30% da duração do segmento
        else if ratio < 0.3 {
            self.consecutive_fast_count += 1;
            self.consecutive_slow_count = 0;

            if self.consecutive_fast_count >= self.hysteresis_threshold
                && self.current_index + 1 < self.variants.len()
            {
                let next_variant = &self.variants[self.current_index + 1];
                // Só sobe se a banda medida cobrir a taxa necessária da próxima variante com margem (1.3x)
                if measured_bps > (next_variant.bandwidth as f64 * 1.3) as u64 {
                    self.current_index += 1;
                    self.consecutive_fast_count = 0;
                    log::info!(
                        "HLS ABR: Aumentando qualidade para variante {} (banda estimada: {} kbps)",
                        self.current_index,
                        measured_bps / 1000
                    );
                    return true;
                }
            }
        } else {
            // Estável
            self.consecutive_slow_count = 0;
            self.consecutive_fast_count = 0;
        }

        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_variants() -> Vec<HlsVariant> {
        vec![
            HlsVariant { bandwidth: 500_000, resolution: Some((640, 360)), codecs: None, frame_rate: None, url: "360p.m3u8".into() },
            HlsVariant { bandwidth: 2_000_000, resolution: Some((1280, 720)), codecs: None, frame_rate: None, url: "720p.m3u8".into() },
            HlsVariant { bandwidth: 6_000_000, resolution: Some((1920, 1080)), codecs: None, frame_rate: None, url: "1080p.m3u8".into() },
        ]
    }

    #[test]
    fn abr_hysteresis_step_down() {
        let mut abr = AdaptiveBitrateManager::new(test_variants());
        assert_eq!(abr.current_variant_index(), 1); // 720p

        // 2 downloads lentos: ainda não atinge o limiar de 3
        assert!(!abr.record_segment_download(1_000_000, Duration::from_secs(9), 10.0));
        assert!(!abr.record_segment_download(1_000_000, Duration::from_secs(9), 10.0));
        assert_eq!(abr.current_variant_index(), 1);

        // 3º download lento: agora reduz para 360p
        assert!(abr.record_segment_download(1_000_000, Duration::from_secs(9), 10.0));
        assert_eq!(abr.current_variant_index(), 0);
    }

    #[test]
    fn abr_hysteresis_step_up() {
        let mut abr = AdaptiveBitrateManager::new(test_variants());
        abr.current_index = 0; // 360p

        // 3 downloads super rápidos com banda suficiente para cobrir 2Mbps
        for _ in 0..2 {
            assert!(!abr.record_segment_download(1_000_000, Duration::from_millis(1000), 10.0)); // 8Mbps medidos
        }
        assert!(abr.record_segment_download(1_000_000, Duration::from_millis(1000), 10.0));
        assert_eq!(abr.current_variant_index(), 1); // Subiu para 720p
    }

    #[test]
    fn abr_manual_override_locks_variant() {
        let mut abr = AdaptiveBitrateManager::new(test_variants());
        abr.set_manual_override(Some(2)); // Trava 1080p

        // Mesmo com conexões lentas, mantém 1080p
        for _ in 0..5 {
            assert!(!abr.record_segment_download(100_000, Duration::from_secs(10), 10.0));
            assert_eq!(abr.current_variant_index(), 2);
        }
    }
}
