//! Lógica pura de upscaling, cálculo de escalas e heurística do modo Auto.
//!
//! Isola regras matemáticas e de decisão gráfica sem dependências de hardware
//! ou NDK, permitindo testes no host via `cargo test -p media-logic`.

/// Modos de Upscaling selecionáveis pelo usuário ou automáticos.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum UpscalingMode {
    Off = 0,
    Quality = 1,
    Performance = 2,
    Auto = 3,
}

impl UpscalingMode {
    pub fn from_u32(val: u32) -> Self {
        match val {
            1 => UpscalingMode::Quality,
            2 => UpscalingMode::Performance,
            3 => UpscalingMode::Auto,
            _ => UpscalingMode::Off,
        }
    }
}

/// Parâmetros resultantes da avaliação de upscaling para o frame de render.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct UpscalingParams {
    /// Fator de escala da resolução do eye buffer (0.5 a 1.0).
    pub render_scale: f32,
    /// Força de nitidez do shader de vídeo (SGSR1) entre 0.0 e 1.0.
    pub sharpness: f32,
    /// Se o Meta Quest Super Resolution (MQSR) deve ser solicitado ao compositor OpenXR.
    pub enable_mqsr: bool,
    /// Se o kernel SGSR1 avançado deve ser executado no fragment shader.
    pub enable_shader_sgsr: bool,
}

/// Retorna o piso de escala de render imposto pelo estado térmico do headset.
///
/// Thermal level segue o padrão Android / ThermalMonitor:
/// 0: None, 1: Light, 2: Moderate, 3: Severe, 4: Critical, 5: Emergency, 6: Shutdown.
pub fn get_thermal_scale_floor(thermal_level: u32) -> f32 {
    match thermal_level {
        0 | 1 => 1.0f32,
        2 => 0.90f32,
        3 => 0.80f32,
        4..=u32::MAX => 0.70f32,
    }
}

/// Determina se o ScreenMode é esférico/VR (360° ou 180°).
pub fn is_spherical_screen_mode(screen_mode: u32) -> bool {
    // ScreenMode 5..=9 são Sphere360, Sphere180, Sphere360SBS, Sphere360OU, Vr180SBS
    (5..=9).contains(&screen_mode)
}

/// Avalia e resolve os parâmetros de renderização e upscaling para o frame.
pub fn evaluate_upscaling(
    mode: UpscalingMode,
    screen_mode: u32,
    video_width: u32,
    video_height: u32,
    thermal_level: u32,
) -> UpscalingParams {
    let thermal_floor = get_thermal_scale_floor(thermal_level);
    let is_severe_thermal = thermal_level >= 3;

    match mode {
        UpscalingMode::Off => {
            // Em Off, escala nativa (respeitando piso térmico de segurança) e sem filtros adicionais
            UpscalingParams {
                render_scale: thermal_floor.min(1.0),
                sharpness: 0.0,
                enable_mqsr: false,
                enable_shader_sgsr: false,
            }
        }
        UpscalingMode::Quality => {
            // Modo Qualidade: foco em nitidez máxima nos pixels do vídeo.
            // Se o headset estiver em nível térmico severo, degrada graciosamente.
            let render_scale = thermal_floor.min(1.0);
            let sgsr_enabled = !is_severe_thermal;
            let sharpness = if is_severe_thermal { 0.2 } else { 0.5 };

            UpscalingParams {
                render_scale,
                sharpness,
                enable_mqsr: true,
                enable_shader_sgsr: sgsr_enabled,
            }
        }
        UpscalingMode::Performance => {
            // Modo Performance: análogo ao DLSS Performance.
            // Renderiza com escala reduzida (0.80) e usa MQSR para reconstruir no display.
            let base_scale = 0.80f32;
            let render_scale = base_scale.min(thermal_floor);

            UpscalingParams {
                render_scale,
                sharpness: 0.25,
                enable_mqsr: true,
                enable_shader_sgsr: false,
            }
        }
        UpscalingMode::Auto => {
            // Heurística inteligente:
            // 1. Vídeos esféricos 180/360 com alta resolução (ex: 8K ou 6K):
            //    O vídeo já cobre quase 100% do FOV e tem alta densidade de pixels.
            //    Rodar 12 taps de SGSR1 causa sobrecarga desnecessária e superaquecimento.
            //    A melhor estratégia é render scale 0.80 + MQSR no compositor.
            let is_spherical = is_spherical_screen_mode(screen_mode);
            let max_dim = video_width.max(video_height);

            if is_severe_thermal {
                // Em estresse térmico: prioriza arrefecimento
                UpscalingParams {
                    render_scale: thermal_floor.min(0.80),
                    sharpness: 0.0,
                    enable_mqsr: true,
                    enable_shader_sgsr: false,
                }
            } else if is_spherical && max_dim >= 5000 {
                // 6K / 8K VR180 ou 360
                UpscalingParams {
                    render_scale: thermal_floor.min(0.80),
                    sharpness: 0.15,
                    enable_mqsr: true,
                    enable_shader_sgsr: false,
                }
            } else if is_spherical {
                // 4K VR180 ou 360
                UpscalingParams {
                    render_scale: thermal_floor.min(0.90),
                    sharpness: 0.35,
                    enable_mqsr: true,
                    enable_shader_sgsr: true,
                }
            } else if max_dim <= 1920 {
                // Flat 2D ou SBS/OU em 1080p ou menor: maior benefício visual possível
                UpscalingParams {
                    render_scale: thermal_floor.min(1.0),
                    sharpness: 0.60,
                    enable_mqsr: true,
                    enable_shader_sgsr: true,
                }
            } else {
                // Flat 2D ou SBS/OU em 4K
                UpscalingParams {
                    render_scale: thermal_floor.min(1.0),
                    sharpness: 0.35,
                    enable_mqsr: true,
                    enable_shader_sgsr: true,
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mode_from_u32() {
        assert_eq!(UpscalingMode::from_u32(0), UpscalingMode::Off);
        assert_eq!(UpscalingMode::from_u32(1), UpscalingMode::Quality);
        assert_eq!(UpscalingMode::from_u32(2), UpscalingMode::Performance);
        assert_eq!(UpscalingMode::from_u32(3), UpscalingMode::Auto);
        assert_eq!(UpscalingMode::from_u32(99), UpscalingMode::Off);
    }

    #[test]
    fn test_off_mode_keeps_native_scale_and_disables_features() {
        let params = evaluate_upscaling(UpscalingMode::Off, 0, 1920, 1080, 0);
        assert_eq!(params.render_scale, 1.0);
        assert_eq!(params.sharpness, 0.0);
        assert!(!params.enable_mqsr);
        assert!(!params.enable_shader_sgsr);
    }

    #[test]
    fn test_thermal_floor_clamps_render_scale() {
        // Nível severo (3) impõe piso de 0.8
        let params = evaluate_upscaling(UpscalingMode::Quality, 0, 1920, 1080, 3);
        assert_eq!(params.render_scale, 0.80);
        // Em nível severo, filtros caros de shader são desativados
        assert!(!params.enable_shader_sgsr);

        // Nível crítico (4) impõe piso de 0.7
        let params_critical = evaluate_upscaling(UpscalingMode::Off, 0, 1920, 1080, 4);
        assert_eq!(params_critical.render_scale, 0.70);
    }

    #[test]
    fn test_performance_mode_uses_reduced_scale_and_mqsr() {
        let params = evaluate_upscaling(UpscalingMode::Performance, 0, 1920, 1080, 0);
        assert_eq!(params.render_scale, 0.80);
        assert!(params.enable_mqsr);
        assert!(!params.enable_shader_sgsr);
    }

    #[test]
    fn test_auto_mode_for_8k_vr180_favors_performance_and_mqsr() {
        // ScreenMode 9 = Vr180SBS, 8192x4096
        let params = evaluate_upscaling(UpscalingMode::Auto, 9, 8192, 4096, 0);
        assert_eq!(params.render_scale, 0.80);
        assert!(params.enable_mqsr);
        assert!(!params.enable_shader_sgsr);
    }

    #[test]
    fn test_auto_mode_for_1080p_flat_favors_quality_sgsr() {
        // ScreenMode 0 = Flat2D, 1920x1080
        let params = evaluate_upscaling(UpscalingMode::Auto, 0, 1920, 1080, 0);
        assert_eq!(params.render_scale, 1.0);
        assert!(params.enable_mqsr);
        assert!(params.enable_shader_sgsr);
        assert!(params.sharpness >= 0.5);
    }
}
