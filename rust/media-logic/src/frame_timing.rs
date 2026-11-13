//! Decisao pura de "o que fazer com este frame de video decodificado",
//! extraida do closure de sync que vivia inline em
//! `core::playback`'s video thread (`decide_frame_action` replica
//! bit-a-bit a arvore que estava la). Zero dependencia de MediaCodec/
//! threads/canais — so PTS, relogio mestre e a flag de "frame de pouso" de
//! seek entram, uma acao sai. Isso permite fixar com teste de host
//! (`cargo test -p media-logic`) um comportamento que antes so existia
//! dentro de `core`, que nao compila fora do NDK (ver docs no topo de
//! `lib.rs`).

use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum FrameAction {
    /// Frame de pouso de um seek: mostrar na hora, na posicao real onde
    /// caiu, sem esperar o PTS exato (ver PrerollState::take_landing).
    Land,
    /// Frame adiantado: esperar `Duration` antes de liberar/mostrar.
    WaitThenRender(Duration),
    /// A hora de mostrar este frame ja chegou (ou passou por pouco,
    /// dentro da tolerancia de late-skip) — liberar/mostrar agora, sem
    /// esperar.
    RenderNow,
    /// Frame chegou tarde demais pra valer a pena mostrar.
    Drop,
}

/// Minimum late frame drop threshold (5 ms). Prevents overly aggressive frame drops
/// on streams with corrupted or absurdly high reported frame rates (e.g. 1000+ fps).
pub const MIN_LATE_SKIP_SEC: f64 = 0.005;

/// Maximum late frame drop threshold (100 ms). Caps the drop threshold so streams with
/// near-zero or extremely low reported frame rates do not effectively disable late frame dropping.
pub const MAX_LATE_SKIP_SEC: f64 = 0.100;

/// Default fallback late tolerance (20 ms) when stream frame rate is unknown or non-positive.
/// Matches VLC video output behavior (Source F1).
pub const DEFAULT_LATE_SKIP_SEC: f64 = 0.020;

/// Calculates the late frame drop threshold in seconds from stream fps.
///
/// Follows VLC late threshold logic: drops late frames once they are more than one frame period late
/// (`1.0 / fps`), clamped to `[MIN_LATE_SKIP_SEC, MAX_LATE_SKIP_SEC]` (5 ms to 100 ms) to guard
/// against degenerate values from corrupted stream metadata, falling back to 20 ms
/// (`DEFAULT_LATE_SKIP_SEC`) when the frame rate is unknown, non-positive, or not finite.
pub fn late_skip_sec_from_fps(fps: f32) -> f64 {
    if fps > 0.0 && fps.is_finite() {
        (1.0 / (fps as f64)).clamp(MIN_LATE_SKIP_SEC, MAX_LATE_SKIP_SEC)
    } else {
        DEFAULT_LATE_SKIP_SEC
    }
}

/// `pts_sec`/`master_clock` in seconds; `late_skip_sec` is the late tolerance
/// threshold below which a late frame is still rendered instead of dropped.
pub fn decide_frame_action(
    pts_sec: f64,
    master_clock: f64,
    is_landing_frame: bool,
    late_skip_sec: f64,
) -> FrameAction {
    if is_landing_frame {
        return FrameAction::Land;
    }
    let delay = pts_sec - master_clock;
    if delay > 0.0 {
        // Frame adiantado: aguarda o tempo restante. Limitado a 1.0s para
        // que o consumidor durma em fatias curtas (PRESENT_WAIT_SLICE de 10ms)
        // sem travar a thread de decode nem despejar frames futuros a 90 FPS.
        return FrameAction::WaitThenRender(Duration::from_secs_f64(delay.min(1.0)));
    }
    if delay > -late_skip_sec {
        FrameAction::RenderNow
    } else {
        FrameAction::Drop
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const LATE_SKIP: f64 = 0.1;

    #[test]
    fn landing_frame_always_lands_regardless_of_pts() {
        // Mesmo com o relogio mestre bem a frente do PTS (o que pareceria
        // "atrasadissimo" pra um frame normal), um frame de pouso de seek
        // sempre poe na tela na hora.
        assert_eq!(
            decide_frame_action(1.0, 50.0, true, LATE_SKIP),
            FrameAction::Land
        );
    }

    #[test]
    fn frame_slightly_ahead_waits_the_exact_remaining_delay() {
        let action = decide_frame_action(1.05, 1.0, false, LATE_SKIP);
        assert_eq!(
            action,
            FrameAction::WaitThenRender(Duration::from_secs_f64(0.05))
        );
    }

    #[test]
    fn frame_exactly_on_time_renders_without_waiting() {
        // delay == 0.0 nao satisfaz `delay > 0.0`, cai no teste de
        // late-skip: 0.0 > -0.1 é verdadeiro -> RenderNow.
        assert_eq!(
            decide_frame_action(1.0, 1.0, false, LATE_SKIP),
            FrameAction::RenderNow
        );
    }

    #[test]
    fn frame_slightly_late_but_within_tolerance_still_renders() {
        // delay = -0.05, dentro da tolerancia de 0.1s.
        assert_eq!(
            decide_frame_action(0.95, 1.0, false, LATE_SKIP),
            FrameAction::RenderNow
        );
    }

    #[test]
    fn frame_late_beyond_tolerance_is_dropped() {
        // delay = -0.5, muito alem da tolerancia de 0.1s.
        assert_eq!(
            decide_frame_action(0.5, 1.0, false, LATE_SKIP),
            FrameAction::Drop
        );
    }

    #[test]
    fn frame_far_in_the_future_waits_instead_of_dumping() {
        // Frames adiantados (delay >= 1.0s) aguardam com teto de 1.0s em vez de
        // serem despejados prematuramente com RenderNow, evitando que o buffer
        // se esgote a 90 FPS em conteudos 24fps.
        let action = decide_frame_action(2.5, 1.0, false, LATE_SKIP);
        assert_eq!(
            action,
            FrameAction::WaitThenRender(Duration::from_secs_f64(1.0))
        );
    }

    #[test]
    fn frame_twenty_ms_late_at_60fps_is_dropped() {
        let late_skip = late_skip_sec_from_fps(60.0);
        // At 60 fps (~16.67 ms duration), a frame 20 ms late exceeds 1 frame period and must be dropped.
        assert_eq!(
            decide_frame_action(1.0 - 0.020, 1.0, false, late_skip),
            FrameAction::Drop
        );
    }

    #[test]
    fn frame_twenty_ms_late_at_24fps_is_rendered() {
        let late_skip = late_skip_sec_from_fps(24.0);
        // At 24 fps (~41.67 ms duration), a frame 20 ms late is within 1 frame period and rendered.
        assert_eq!(
            decide_frame_action(1.0 - 0.020, 1.0, false, late_skip),
            FrameAction::RenderNow
        );
    }

    #[test]
    fn unknown_frame_rate_uses_twenty_ms_threshold() {
        assert_eq!(late_skip_sec_from_fps(0.0), 0.020);
        assert_eq!(late_skip_sec_from_fps(-5.0), 0.020);
        assert_eq!(late_skip_sec_from_fps(f32::NAN), 0.020);

        let late_skip = late_skip_sec_from_fps(0.0);
        // 19 ms late is within the 20 ms threshold -> RenderNow
        assert_eq!(
            decide_frame_action(1.0 - 0.019, 1.0, false, late_skip),
            FrameAction::RenderNow
        );
        // 21 ms late exceeds the 20 ms threshold -> Drop
        assert_eq!(
            decide_frame_action(1.0 - 0.021, 1.0, false, late_skip),
            FrameAction::Drop
        );
    }

    #[test]
    fn excessively_high_fps_is_clamped_to_min_threshold() {
        assert_eq!(late_skip_sec_from_fps(1000.0), MIN_LATE_SKIP_SEC);
        assert_eq!(late_skip_sec_from_fps(90000.0), MIN_LATE_SKIP_SEC);

        let late_skip = late_skip_sec_from_fps(1000.0);
        // 4 ms late is within the 5 ms threshold -> RenderNow
        assert_eq!(
            decide_frame_action(1.0 - 0.004, 1.0, false, late_skip),
            FrameAction::RenderNow
        );
        // 6 ms late exceeds the 5 ms threshold -> Drop
        assert_eq!(
            decide_frame_action(1.0 - 0.006, 1.0, false, late_skip),
            FrameAction::Drop
        );
    }

    #[test]
    fn near_zero_fps_is_clamped_to_max_threshold() {
        assert_eq!(late_skip_sec_from_fps(0.001), MAX_LATE_SKIP_SEC);
        assert_eq!(late_skip_sec_from_fps(f32::MIN_POSITIVE), MAX_LATE_SKIP_SEC);

        let late_skip = late_skip_sec_from_fps(0.001);
        // 99 ms late is within the 100 ms threshold -> RenderNow
        assert_eq!(
            decide_frame_action(1.0 - 0.099, 1.0, false, late_skip),
            FrameAction::RenderNow
        );
        // 101 ms late exceeds the 100 ms threshold -> Drop
        assert_eq!(
            decide_frame_action(1.0 - 0.101, 1.0, false, late_skip),
            FrameAction::Drop
        );
    }
}
