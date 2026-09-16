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

/// `pts_sec`/`master_clock` em segundos; `late_skip_sec` e a tolerancia
/// (`LATE_FRAME_RENDER_SKIP_SEC` em playback.rs) abaixo da qual um frame
/// atrasado ainda e mostrado em vez de descartado.
///
/// Replica fielmente a arvore que existia inline no `sync_callback` de
/// `video_thread` (playback.rs): se `is_landing_frame`, poe na tela
/// incondicionalmente; senao calcula `delay = pts_sec - master_clock` e,
/// **so se `0.0 < delay < 1.0`**, manda esperar esse tanto — um `delay >=
/// 1.0s` PULA a espera e cai direto no teste de late-skip abaixo (esse
/// comportamento already existia no codigo original e e preservado aqui de
/// proposito, ver o teste `frame_far_in_the_future_skips_the_wait_but_still_renders`).
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
    if delay > 0.0 && delay < 1.0 {
        return FrameAction::WaitThenRender(Duration::from_secs_f64(delay));
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
        assert_eq!(action, FrameAction::WaitThenRender(Duration::from_secs_f64(0.05)));
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
    fn frame_far_in_the_future_skips_the_wait_but_still_renders() {
        // Comportamento preservado do codigo original (playback.rs): um
        // delay >= 1.0s NAO gera WaitThenRender (evita dormir 1.5s de uma
        // vez) — cai direto no teste de late-skip, que aqui da RenderNow
        // porque o delay e positivo (bem longe de ficar abaixo de
        // -late_skip_sec). Uma regressao aqui faria o pipeline voltar a
        // dormir por segundos inteiros de uma vez.
        let action = decide_frame_action(2.5, 1.0, false, LATE_SKIP);
        assert_eq!(action, FrameAction::RenderNow);
    }
}
