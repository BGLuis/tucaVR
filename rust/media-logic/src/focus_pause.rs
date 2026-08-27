//! Máquina de estados pura para pausa automática por perda de foco (F0–F4).
//!
//! Modela o comportamento de auto_paused extraído de `core::playback`:
//! - Se estava tocando ao perder o foco, marca `auto_paused = true` e sinaliza pausa.
//! - Se o usuário já tinha pausado manualmente, `auto_paused` permanece `false`.
//! - Ao recuperar o foco, se `auto_paused == true`, desmarca e sinaliza retomada.
//! - Se `stop()` for chamado durante a pausa, `auto_paused` é zerado para não herdar
//!   o estado na próxima mídia.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct FocusPauseManager {
    auto_paused: bool,
}

impl FocusPauseManager {
    pub const fn new() -> Self {
        Self { auto_paused: false }
    }

    pub fn is_auto_paused(&self) -> bool {
        self.auto_paused
    }

    /// Chamado ao perder o foco (OpenXR XR_SESSION_STATE_STOPPING).
    /// Retorna `true` se a reprodução deve ser pausada.
    pub fn on_focus_lost(&mut self, is_playing: bool) -> bool {
        if is_playing {
            self.auto_paused = true;
            true
        } else {
            false
        }
    }

    /// Chamado ao recuperar o foco (OpenXR XR_SESSION_STATE_FOCUSED).
    /// Retorna `true` se a reprodução deve ser retomada automaticamente.
    pub fn on_focus_gained(&mut self) -> bool {
        if self.auto_paused {
            self.auto_paused = false;
            true
        } else {
            false
        }
    }

    /// Chamado quando a reprodução é encerrada (stop) ou nova mídia inicia.
    pub fn on_stop(&mut self) {
        self.auto_paused = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pause_on_focus_lost_when_playing_and_resume_on_focus_gained() {
        let mut mgr = FocusPauseManager::new();

        // Vídeo está tocando: perder foco deve pausar
        assert!(mgr.on_focus_lost(true));
        assert!(mgr.is_auto_paused());

        // Recuperar foco deve retomar
        assert!(mgr.on_focus_gained());
        assert!(!mgr.is_auto_paused());
    }

    #[test]
    fn test_user_paused_video_is_not_resumed_on_focus_gained() {
        let mut mgr = FocusPauseManager::new();

        // Vídeo já pausado pelo usuário (is_playing = false): perder foco NÃO marca auto_paused
        assert!(!mgr.on_focus_lost(false));
        assert!(!mgr.is_auto_paused());

        // Recuperar foco NÃO deve despausar vídeo pausado pelo usuário
        assert!(!mgr.on_focus_gained());
        assert!(!mgr.is_auto_paused());
    }

    #[test]
    fn test_consecutive_focus_lost_calls_maintain_single_auto_pause() {
        let mut mgr = FocusPauseManager::new();

        assert!(mgr.on_focus_lost(true));
        // Segundo focus lost enquanto já em pausa
        assert!(!mgr.on_focus_lost(false));
        assert!(mgr.is_auto_paused());

        // Primeiro gained retoma
        assert!(mgr.on_focus_gained());
        assert!(!mgr.is_auto_paused());

        // Segundo gained não faz nada
        assert!(!mgr.on_focus_gained());
    }

    #[test]
    fn test_stop_clears_auto_paused_state() {
        let mut mgr = FocusPauseManager::new();

        assert!(mgr.on_focus_lost(true));
        assert!(mgr.is_auto_paused());

        // Usuário ou sistema para o vídeo
        mgr.on_stop();
        assert!(!mgr.is_auto_paused());

        // Recuperar foco posterior não retoma nada
        assert!(!mgr.on_focus_gained());
    }
}
