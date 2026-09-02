//! Controlador de Qualidade Adaptativa (Adaptive Quality) para reprodução VR/8K.
//!
//! Isola as regras de histerese, orçamentos de frame e GPU time, pisos térmicos
//! e mapeamento de Foveated Rendering (FFR), permitindo testes 100% no host
//! via `cargo test -p media-logic`.

use crate::upscaling::{get_thermal_scale_floor, UpscalingMode};

/// Níveis discretos de qualidade adaptativa do sistema.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u32)]
pub enum QualityLevel {
    Ultra = 0,
    High = 1,
    Medium = 2,
    Low = 3,
    Emergency = 4,
}

impl QualityLevel {
    pub fn from_u32(val: u32) -> Self {
        match val {
            0 => QualityLevel::Ultra,
            1 => QualityLevel::High,
            2 => QualityLevel::Medium,
            3 => QualityLevel::Low,
            _ => QualityLevel::Emergency,
        }
    }

    pub fn as_u32(&self) -> u32 {
        *self as u32
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            QualityLevel::Ultra => "Ultra",
            QualityLevel::High => "High",
            QualityLevel::Medium => "Medium",
            QualityLevel::Low => "Low",
            QualityLevel::Emergency => "Emergency",
        }
    }

    /// Fator de escala base do eye buffer para este nível.
    pub fn base_render_scale(&self) -> f32 {
        match self {
            QualityLevel::Ultra => 1.0f32,
            QualityLevel::High => 1.0f32,
            QualityLevel::Medium => 0.90f32,
            QualityLevel::Low => 0.80f32,
            QualityLevel::Emergency => 0.70f32,
        }
    }

    /// Nível de foveation OpenXR FB correspondente:
    /// 0: NONE, 1: LOW, 2: MEDIUM, 3: HIGH.
    pub fn foveation_level(&self) -> u32 {
        match self {
            QualityLevel::Ultra => 0,     // XR_FOVEATION_LEVEL_NONE_FB
            QualityLevel::High => 1,      // XR_FOVEATION_LEVEL_LOW_FB
            QualityLevel::Medium => 2,    // XR_FOVEATION_LEVEL_MEDIUM_FB
            QualityLevel::Low => 3,       // XR_FOVEATION_LEVEL_HIGH_FB
            QualityLevel::Emergency => 3, // XR_FOVEATION_LEVEL_HIGH_FB + verticalOffset
        }
    }

    /// Deslocamento vertical de foveação em graus (aplicado no nível Emergency).
    pub fn foveation_vertical_offset(&self) -> f32 {
        match self {
            QualityLevel::Emergency => 10.0f32,
            _ => 0.0f32,
        }
    }

    /// Taxa de atualização alvo recomendada para o compositor OpenXR (em Hz).
    pub fn target_fps(&self) -> f32 {
        match self {
            QualityLevel::Ultra | QualityLevel::High | QualityLevel::Medium => 90.0f32,
            QualityLevel::Low | QualityLevel::Emergency => 72.0f32,
        }
    }

    /// Degrada 1 nível em direção ao Emergency.
    pub fn step_down(&self) -> Self {
        match self {
            QualityLevel::Ultra => QualityLevel::High,
            QualityLevel::High => QualityLevel::Medium,
            QualityLevel::Medium => QualityLevel::Low,
            QualityLevel::Low | QualityLevel::Emergency => QualityLevel::Emergency,
        }
    }

    /// Promove 1 nível em direção ao Ultra.
    pub fn step_up(&self) -> Self {
        match self {
            QualityLevel::Ultra | QualityLevel::High => QualityLevel::Ultra,
            QualityLevel::Medium => QualityLevel::High,
            QualityLevel::Low => QualityLevel::Medium,
            QualityLevel::Emergency => QualityLevel::Low,
        }
    }
}

/// Motivo da transição de nível de qualidade.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum QualityTransitionReason {
    None = 0,
    ThermalPressure = 1,
    GpuOverload = 2,
    FramePacingLag = 3,
    DroppedFrames = 4,
    StableRecovery = 5,
    ManualOverride = 6,
}

impl QualityTransitionReason {
    pub fn as_str(&self) -> &'static str {
        match self {
            QualityTransitionReason::None => "None",
            QualityTransitionReason::ThermalPressure => "ThermalPressure",
            QualityTransitionReason::GpuOverload => "GpuOverload",
            QualityTransitionReason::FramePacingLag => "FramePacingLag",
            QualityTransitionReason::DroppedFrames => "DroppedFrames",
            QualityTransitionReason::StableRecovery => "StableRecovery",
            QualityTransitionReason::ManualOverride => "ManualOverride",
        }
    }
}

/// Amostra de telemetria coletada no render loop a cada intervalo de avaliação (~1 Hz).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct QualitySample {
    /// Nível térmico reportado pelo Android PowerManager (0=None a 6=Shutdown).
    pub thermal_level: u32,
    /// GPU time médio suavizado via timestamps Vulkan (em ms).
    pub smoothed_gpu_time_ms: f32,
    /// Duração do último frame previsto pelo compositor OpenXR (em ms).
    pub frame_time_ms: f32,
    /// Taxa de frames descartados por atraso na janela recente (fps).
    pub dropped_fps: f32,
    /// Taxa de atualização atual do display (ex: 90.0 ou 72.0 Hz).
    pub target_fps: f32,
}

/// Ação resultante de uma avaliação de qualidade adaptativa.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QualityAction {
    Maintain,
    Degrade(QualityLevel, QualityTransitionReason),
    Upgrade(QualityLevel, QualityTransitionReason),
}

/// Parâmetros finais calculados para aplicação direta no render loop.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct QualityResolvedParams {
    pub level: QualityLevel,
    pub render_scale: f32,
    pub foveation_level: u32,
    pub foveation_vertical_offset: f32,
    pub target_fps: f32,
    pub sharpness: f32,
    pub enable_mqsr: bool,
    pub enable_shader_sgsr: bool,
    pub last_reason: QualityTransitionReason,
}

/// Controlador adaptativo com histerese assimétrica para estabilização de performance.
#[derive(Debug, Clone)]
pub struct QualityController {
    current_level: QualityLevel,
    last_reason: QualityTransitionReason,
    /// Amostras consecutivas com estresse de GPU.
    gpu_stress_count: u32,
    /// Amostras consecutivas com atraso de frame pacing.
    pacing_stress_count: u32,
    /// Segundos / amostras consecutivas em condições saudáveis para justificar subida.
    stable_healthy_samples: u32,
    /// Se o modo adaptativo automático está ativo (true por padrão).
    enabled: bool,
}

impl Default for QualityController {
    fn default() -> Self {
        Self::new()
    }
}

impl QualityController {
    /// Segundos estáveis necessários para promover o nível de qualidade (histerese de subida).
    pub const UPGRADE_STABILITY_REQUIRED_SAMPLES: u32 = 30;
    /// Amostras consecutivas de sobrecarga de GPU para disparar degradação.
    pub const GPU_STRESS_THRESHOLD_SAMPLES: u32 = 2;
    /// Amostras consecutivas de frame pacing lag para disparar degradação.
    pub const PACING_STRESS_THRESHOLD_SAMPLES: u32 = 2;
    /// Limiar de dropped FPS para degradação imediata em 1 amostra.
    pub const DROPPED_FPS_STRESS_THRESHOLD: f32 = 3.0f32;

    pub fn new() -> Self {
        Self {
            current_level: QualityLevel::High,
            last_reason: QualityTransitionReason::None,
            gpu_stress_count: 0,
            pacing_stress_count: 0,
            stable_healthy_samples: 0,
            enabled: true,
        }
    }

    pub fn current_level(&self) -> QualityLevel {
        self.current_level
    }

    pub fn last_reason(&self) -> QualityTransitionReason {
        self.last_reason
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
        if !enabled {
            self.gpu_stress_count = 0;
            self.pacing_stress_count = 0;
            self.stable_healthy_samples = 0;
        }
    }

    pub fn set_manual_level(&mut self, level: QualityLevel) {
        self.current_level = level;
        self.last_reason = QualityTransitionReason::ManualOverride;
        self.gpu_stress_count = 0;
        self.pacing_stress_count = 0;
        self.stable_healthy_samples = 0;
    }

    /// Avalia a amostra de performance e atualiza o estado interno do controlador.
    pub fn evaluate(&mut self, sample: &QualitySample) -> QualityAction {
        if !self.enabled {
            return QualityAction::Maintain;
        }

        let target_fps = if sample.target_fps > 30.0 {
            sample.target_fps
        } else {
            90.0
        };
        let frame_interval_ms = 1000.0f32 / target_fps;

        // Limiares relativos ao target_fps atual:
        let pacing_budget_ms = frame_interval_ms * 1.20f32;
        let gpu_budget_ms = frame_interval_ms * 0.85f32;
        let gpu_healthy_ms = frame_interval_ms * 0.65f32;
        let pacing_healthy_ms = frame_interval_ms * 0.95f32;

        // 1. Verificação Térmica Direta (Hard Thermal Constraints):
        if sample.thermal_level >= 4 && self.current_level < QualityLevel::Emergency {
            self.current_level = QualityLevel::Emergency;
            self.last_reason = QualityTransitionReason::ThermalPressure;
            self.reset_counters();
            return QualityAction::Degrade(self.current_level, self.last_reason);
        } else if sample.thermal_level == 3 && self.current_level < QualityLevel::Low {
            self.current_level = QualityLevel::Low;
            self.last_reason = QualityTransitionReason::ThermalPressure;
            self.reset_counters();
            return QualityAction::Degrade(self.current_level, self.last_reason);
        } else if sample.thermal_level == 2 && self.current_level < QualityLevel::Medium {
            self.current_level = QualityLevel::Medium;
            self.last_reason = QualityTransitionReason::ThermalPressure;
            self.reset_counters();
            return QualityAction::Degrade(self.current_level, self.last_reason);
        }

        // 2. Verificação de Descarte Severo de Frames:
        if sample.dropped_fps >= Self::DROPPED_FPS_STRESS_THRESHOLD
            && self.current_level < QualityLevel::Emergency
        {
            let next_level = self.current_level.step_down();
            self.current_level = next_level;
            self.last_reason = QualityTransitionReason::DroppedFrames;
            self.reset_counters();
            return QualityAction::Degrade(self.current_level, self.last_reason);
        }

        // 3. Verificação de Sobrecarga de GPU:
        if sample.smoothed_gpu_time_ms > gpu_budget_ms {
            self.gpu_stress_count += 1;
            self.stable_healthy_samples = 0;
            if self.gpu_stress_count >= Self::GPU_STRESS_THRESHOLD_SAMPLES
                && self.current_level < QualityLevel::Emergency
            {
                let next_level = self.current_level.step_down();
                self.current_level = next_level;
                self.last_reason = QualityTransitionReason::GpuOverload;
                self.reset_counters();
                return QualityAction::Degrade(self.current_level, self.last_reason);
            }
        } else {
            self.gpu_stress_count = 0;
        }

        // 4. Verificação de Pacing Lag (Compositor Frame Time):
        if sample.frame_time_ms > pacing_budget_ms {
            self.pacing_stress_count += 1;
            self.stable_healthy_samples = 0;
            if self.pacing_stress_count >= Self::PACING_STRESS_THRESHOLD_SAMPLES
                && self.current_level < QualityLevel::Emergency
            {
                let next_level = self.current_level.step_down();
                self.current_level = next_level;
                self.last_reason = QualityTransitionReason::FramePacingLag;
                self.reset_counters();
                return QualityAction::Degrade(self.current_level, self.last_reason);
            }
        } else {
            self.pacing_stress_count = 0;
        }

        // 5. Verificação de Subida Estável (Upgrade Hysteresis):
        let is_thermal_permitting_upgrade = match self.current_level {
            QualityLevel::Emergency => sample.thermal_level < 4,
            QualityLevel::Low => sample.thermal_level < 3,
            QualityLevel::Medium => sample.thermal_level < 2,
            QualityLevel::High | QualityLevel::Ultra => sample.thermal_level <= 1,
        };

        let is_healthy = is_thermal_permitting_upgrade
            && sample.dropped_fps < 0.5f32
            && sample.frame_time_ms < pacing_healthy_ms
            && sample.smoothed_gpu_time_ms < gpu_healthy_ms;

        if is_healthy {
            self.stable_healthy_samples += 1;
            if self.stable_healthy_samples >= Self::UPGRADE_STABILITY_REQUIRED_SAMPLES
                && self.current_level > QualityLevel::Ultra
            {
                let next_level = self.current_level.step_up();
                self.current_level = next_level;
                self.last_reason = QualityTransitionReason::StableRecovery;
                self.reset_counters();
                return QualityAction::Upgrade(self.current_level, self.last_reason);
            }
        } else if sample.smoothed_gpu_time_ms > gpu_healthy_ms
            || sample.frame_time_ms > pacing_healthy_ms
            || sample.dropped_fps >= 0.5f32
        {
            // Condição marginal ou estresse: reinicia o acúmulo de estabilidade para evitar flapping
            self.stable_healthy_samples = 0;
        }

        QualityAction::Maintain
    }

    fn reset_counters(&mut self) {
        self.gpu_stress_count = 0;
        self.pacing_stress_count = 0;
        self.stable_healthy_samples = 0;
    }

    /// Resolve os parâmetros de renderização finais combinando o nível adaptativo com a precedência térmica.
    pub fn resolve_params(
        &self,
        sample_thermal_level: u32,
        base_upscaling_mode: UpscalingMode,
        screen_mode: u32,
        video_width: u32,
        video_height: u32,
    ) -> QualityResolvedParams {
        let thermal_floor = get_thermal_scale_floor(sample_thermal_level);
        let base_scale = match base_upscaling_mode {
            UpscalingMode::Performance => 0.80f32.min(self.current_level.base_render_scale()),
            _ => self.current_level.base_render_scale(),
        };

        // Regra de ouro de precedência: Piso térmico é hard floor inegociável
        let effective_render_scale = base_scale.min(thermal_floor);

        // Resolução de filtros de upscaling (MQSR / SGSR1)
        let is_severe_thermal = sample_thermal_level >= 3;
        let is_spherical = (5..=9).contains(&screen_mode);
        let max_dim = video_width.max(video_height);

        let (enable_mqsr, enable_shader_sgsr, sharpness) = match base_upscaling_mode {
            UpscalingMode::Off => (false, false, 0.0f32),
            UpscalingMode::Quality => {
                let sgsr = !is_severe_thermal && self.current_level <= QualityLevel::High;
                let sharp = if is_severe_thermal { 0.2f32 } else { 0.5f32 };
                (true, sgsr, sharp)
            }
            UpscalingMode::Performance => (true, false, 0.25f32),
            UpscalingMode::Auto => {
                if is_severe_thermal || self.current_level >= QualityLevel::Low {
                    (true, false, 0.0f32)
                } else if is_spherical && max_dim >= 5000 {
                    (true, false, 0.15f32)
                } else if is_spherical {
                    (true, true, 0.35f32)
                } else if max_dim <= 1920 {
                    (true, true, 0.60f32)
                } else {
                    (true, true, 0.35f32)
                }
            }
        };

        QualityResolvedParams {
            level: self.current_level,
            render_scale: effective_render_scale,
            foveation_level: self.current_level.foveation_level(),
            foveation_vertical_offset: self.current_level.foveation_vertical_offset(),
            target_fps: self.current_level.target_fps(),
            sharpness,
            enable_mqsr,
            enable_shader_sgsr,
            last_reason: self.last_reason,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_quality_level_properties() {
        assert_eq!(QualityLevel::Ultra.base_render_scale(), 1.0);
        assert_eq!(QualityLevel::High.base_render_scale(), 1.0);
        assert_eq!(QualityLevel::Medium.base_render_scale(), 0.90);
        assert_eq!(QualityLevel::Low.base_render_scale(), 0.80);
        assert_eq!(QualityLevel::Emergency.base_render_scale(), 0.70);

        assert_eq!(QualityLevel::Ultra.foveation_level(), 0);
        assert_eq!(QualityLevel::High.foveation_level(), 1);
        assert_eq!(QualityLevel::Medium.foveation_level(), 2);
        assert_eq!(QualityLevel::Low.foveation_level(), 3);
        assert_eq!(QualityLevel::Emergency.foveation_level(), 3);
        assert_eq!(QualityLevel::Emergency.foveation_vertical_offset(), 10.0);
        assert_eq!(QualityLevel::Low.foveation_vertical_offset(), 0.0);

        assert_eq!(QualityLevel::Ultra.target_fps(), 90.0);
        assert_eq!(QualityLevel::Medium.target_fps(), 90.0);
        assert_eq!(QualityLevel::Low.target_fps(), 72.0);
        assert_eq!(QualityLevel::Emergency.target_fps(), 72.0);
    }

    #[test]
    fn test_quality_level_monotonicity() {
        let levels = [
            QualityLevel::Ultra,
            QualityLevel::High,
            QualityLevel::Medium,
            QualityLevel::Low,
            QualityLevel::Emergency,
        ];
        for i in 0..levels.len() - 1 {
            assert!(levels[i].base_render_scale() >= levels[i + 1].base_render_scale());
            assert!(levels[i].foveation_level() <= levels[i + 1].foveation_level());
            assert!(levels[i].target_fps() >= levels[i + 1].target_fps());
        }
    }

    #[test]
    fn test_thermal_hard_floor_immediate_degradation() {
        let mut controller = QualityController::new();
        assert_eq!(controller.current_level(), QualityLevel::High);

        // Nível térmico crítico (4) força Emergency imediatamente em 1 amostra
        let sample_critical = QualitySample {
            thermal_level: 4,
            smoothed_gpu_time_ms: 5.0,
            frame_time_ms: 11.0,
            dropped_fps: 0.0,
            target_fps: 90.0,
        };
        let action = controller.evaluate(&sample_critical);
        assert_eq!(
            action,
            QualityAction::Degrade(QualityLevel::Emergency, QualityTransitionReason::ThermalPressure)
        );
        assert_eq!(controller.current_level(), QualityLevel::Emergency);

        let params = controller.resolve_params(4, UpscalingMode::Auto, 0, 1920, 1080);
        assert_eq!(params.render_scale, 0.70);
    }

    #[test]
    fn test_thermal_floor_overrules_good_metrics_on_upgrade() {
        let mut controller = QualityController::new();
        controller.set_manual_level(QualityLevel::Emergency);

        // Métricas de performance excelentes, mas thermal_level = 4
        let sample = QualitySample {
            thermal_level: 4,
            smoothed_gpu_time_ms: 2.0,
            frame_time_ms: 11.0,
            dropped_fps: 0.0,
            target_fps: 90.0,
        };

        // Mesmo após 50 amostras excelentes, não pode subir enquanto thermal >= 4
        for _ in 0..50 {
            let action = controller.evaluate(&sample);
            assert_eq!(action, QualityAction::Maintain);
        }
        assert_eq!(controller.current_level(), QualityLevel::Emergency);
    }

    #[test]
    fn test_dropped_fps_triggers_fast_degradation() {
        let mut controller = QualityController::new();
        assert_eq!(controller.current_level(), QualityLevel::High);

        let sample_drop = QualitySample {
            thermal_level: 0,
            smoothed_gpu_time_ms: 6.0,
            frame_time_ms: 11.0,
            dropped_fps: 4.5,
            target_fps: 90.0,
        };
        let action = controller.evaluate(&sample_drop);
        assert_eq!(
            action,
            QualityAction::Degrade(QualityLevel::Medium, QualityTransitionReason::DroppedFrames)
        );
        assert_eq!(controller.current_level(), QualityLevel::Medium);
    }

    #[test]
    fn test_gpu_overload_degrades_after_two_samples() {
        let mut controller = QualityController::new();
        assert_eq!(controller.current_level(), QualityLevel::High);

        // A 90Hz (~11.1ms por frame), gpu_budget é ~9.44ms. Amostra com 10.5ms é sobrecarga.
        let sample_gpu_stress = QualitySample {
            thermal_level: 0,
            smoothed_gpu_time_ms: 10.5,
            frame_time_ms: 11.0,
            dropped_fps: 0.0,
            target_fps: 90.0,
        };

        // 1ª amostra: detecta estresse, mas não degrada ainda
        let action1 = controller.evaluate(&sample_gpu_stress);
        assert_eq!(action1, QualityAction::Maintain);
        assert_eq!(controller.current_level(), QualityLevel::High);

        // 2ª amostra consecutiva: degrada para Medium
        let action2 = controller.evaluate(&sample_gpu_stress);
        assert_eq!(
            action2,
            QualityAction::Degrade(QualityLevel::Medium, QualityTransitionReason::GpuOverload)
        );
        assert_eq!(controller.current_level(), QualityLevel::Medium);
    }

    #[test]
    fn test_frame_pacing_budget_scales_with_target_fps() {
        let mut controller = QualityController::new();
        controller.set_manual_level(QualityLevel::Low);

        // A 72Hz, o intervalo de frame é ~13.88ms. Orçamento de pacing com margem de 1.2x é ~16.66ms.
        // Logo, frame_time_ms de 14.5ms é SAUDÁVEL a 72Hz!
        let sample_72hz = QualitySample {
            thermal_level: 0,
            smoothed_gpu_time_ms: 7.0,
            frame_time_ms: 14.5,
            dropped_fps: 0.0,
            target_fps: 72.0,
        };

        // Não deve degradar sob 14.5ms a 72Hz
        let action = controller.evaluate(&sample_72hz);
        assert_eq!(action, QualityAction::Maintain);
        assert_eq!(controller.current_level(), QualityLevel::Low);

        // Já a 90Hz (orçamento de 13.33ms), 14.5ms é lag!
        controller.set_manual_level(QualityLevel::High);
        let sample_90hz_lag = QualitySample {
            thermal_level: 0,
            smoothed_gpu_time_ms: 7.0,
            frame_time_ms: 14.5,
            dropped_fps: 0.0,
            target_fps: 90.0,
        };
        controller.evaluate(&sample_90hz_lag);
        let action_lag = controller.evaluate(&sample_90hz_lag);
        assert_eq!(
            action_lag,
            QualityAction::Degrade(QualityLevel::Medium, QualityTransitionReason::FramePacingLag)
        );
    }

    #[test]
    fn test_upgrade_requires_30_stable_healthy_samples() {
        let mut controller = QualityController::new();
        controller.set_manual_level(QualityLevel::Medium);

        let healthy_sample = QualitySample {
            thermal_level: 0,
            smoothed_gpu_time_ms: 5.0,
            frame_time_ms: 10.0,
            dropped_fps: 0.0,
            target_fps: 90.0,
        };

        // Das amostras 1 a 29: mantém Medium
        for i in 1..30 {
            let action = controller.evaluate(&healthy_sample);
            assert_eq!(action, QualityAction::Maintain, "Amostra {i} não deve subir prematuramente");
            assert_eq!(controller.current_level(), QualityLevel::Medium);
        }

        // Amostra 30: dispara upgrade para High
        let action30 = controller.evaluate(&healthy_sample);
        assert_eq!(
            action30,
            QualityAction::Upgrade(QualityLevel::High, QualityTransitionReason::StableRecovery)
        );
        assert_eq!(controller.current_level(), QualityLevel::High);
    }

    #[test]
    fn test_flapping_guard_resets_stability_counter_on_intermittent_spikes() {
        let mut controller = QualityController::new();
        controller.set_manual_level(QualityLevel::Medium);

        let healthy_sample = QualitySample {
            thermal_level: 0,
            smoothed_gpu_time_ms: 5.0,
            frame_time_ms: 10.0,
            dropped_fps: 0.0,
            target_fps: 90.0,
        };
        let spike_sample = QualitySample {
            thermal_level: 0,
            smoothed_gpu_time_ms: 8.5, // Acima do limiar saudável de upgrade
            frame_time_ms: 10.0,
            dropped_fps: 0.0,
            target_fps: 90.0,
        };

        // Acumula 20 amostras saudáveis
        for _ in 0..20 {
            controller.evaluate(&healthy_sample);
        }
        // Ocorre 1 spike
        controller.evaluate(&spike_sample);

        // Mais 15 amostras saudáveis (total de 35 amostras no tempo, mas apenas 15 contínuas após o spike)
        for _ in 0..15 {
            let action = controller.evaluate(&healthy_sample);
            assert_eq!(action, QualityAction::Maintain);
        }
        assert_eq!(controller.current_level(), QualityLevel::Medium);
    }
}
