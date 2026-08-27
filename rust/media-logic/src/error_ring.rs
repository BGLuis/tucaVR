//! Buffer circular para erros de reprodução (N6 do plano de telemetria).
//!
//! Substitui o slot único e destrutivo (`LAST_PLAYBACK_ERROR`) por um anel
//! de capacidade limitada que mantém o histórico recente de erros com timestamps,
//! preservando a compatibilidade com o polling de `Toast` da UI.

use std::collections::VecDeque;
use std::sync::Mutex;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlaybackError {
    pub message: String,
    pub timestamp_ms: u64,
    pub session_id: Option<String>,
}

pub struct ErrorRingBuffer {
    capacity: usize,
    errors: Mutex<VecDeque<PlaybackError>>,
    unconsumed: Mutex<Option<String>>,
}

impl ErrorRingBuffer {
    pub const DEFAULT_CAPACITY: usize = 16;

    pub fn new(capacity: usize) -> Self {
        Self {
            capacity: capacity.max(1),
            errors: Mutex::new(VecDeque::with_capacity(capacity)),
            unconsumed: Mutex::new(None),
        }
    }

    /// Registra um novo erro no anel circular e marca como não-consumido para a UI.
    pub fn push(&self, message: String, timestamp_ms: u64, session_id: Option<String>) {
        let entry = PlaybackError {
            message: message.clone(),
            timestamp_ms,
            session_id,
        };

        if let Ok(mut lock) = self.errors.lock() {
            if lock.len() >= self.capacity {
                lock.pop_front();
            }
            lock.push_back(entry);
        }

        if let Ok(mut unconsumed_lock) = self.unconsumed.lock() {
            *unconsumed_lock = Some(message);
        }
    }

    /// Consome (lê e limpa) o último erro registrado para exibição no Toast.
    /// Preserva o histórico dentro do buffer circular.
    pub fn take_latest_unconsumed(&self) -> Option<String> {
        self.unconsumed.lock().ok().and_then(|mut slot| slot.take())
    }

    /// Retorna uma cópia do erro mais recente sem consumir o slot do Toast.
    pub fn peek_latest(&self) -> Option<PlaybackError> {
        self.errors.lock().ok().and_then(|lock| lock.back().cloned())
    }

    /// Retorna o total de erros armazenados atualmente no anel.
    pub fn count(&self) -> usize {
        self.errors.lock().map(|lock| lock.len()).unwrap_or(0)
    }

    /// Retorna todos os erros em ordem cronológica (mais antigo primeiro).
    pub fn all(&self) -> Vec<PlaybackError> {
        self.errors.lock().map(|lock| lock.iter().cloned().collect()).unwrap_or_default()
    }

    /// Limpa todo o histórico de erros.
    pub fn clear(&self) {
        if let Ok(mut lock) = self.errors.lock() {
            lock.clear();
        }
        if let Ok(mut unconsumed_lock) = self.unconsumed.lock() {
            *unconsumed_lock = None;
        }
    }
}

impl Default for ErrorRingBuffer {
    fn default() -> Self {
        Self::new(Self::DEFAULT_CAPACITY)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_push_and_peek_latest() {
        let ring = ErrorRingBuffer::new(3);
        assert_eq!(ring.count(), 0);
        assert!(ring.peek_latest().is_none());

        ring.push("Erro 1".into(), 1000, Some("sess1".into()));
        assert_eq!(ring.count(), 1);
        let err = ring.peek_latest().unwrap();
        assert_eq!(err.message, "Erro 1");
        assert_eq!(err.timestamp_ms, 1000);
        assert_eq!(err.session_id, Some("sess1".into()));
    }

    #[test]
    fn test_capacity_overflow_discards_oldest() {
        let ring = ErrorRingBuffer::new(2);
        ring.push("Erro 1".into(), 100, None);
        ring.push("Erro 2".into(), 200, None);
        ring.push("Erro 3".into(), 300, None);

        assert_eq!(ring.count(), 2);
        let all = ring.all();
        assert_eq!(all.len(), 2);
        assert_eq!(all[0].message, "Erro 2");
        assert_eq!(all[1].message, "Erro 3");
    }

    #[test]
    fn test_take_latest_unconsumed_is_destructive_for_toast_only() {
        let ring = ErrorRingBuffer::new(5);
        ring.push("Erro A".into(), 100, None);

        // Primeiro take deve retornar o erro
        assert_eq!(ring.take_latest_unconsumed(), Some("Erro A".into()));
        // Segundo take deve ser None até que um novo erro chegue
        assert_eq!(ring.take_latest_unconsumed(), None);

        // O histórico permanece no ring
        assert_eq!(ring.count(), 1);
        assert_eq!(ring.peek_latest().unwrap().message, "Erro A");

        // Novo erro repopula o unconsumed
        ring.push("Erro B".into(), 200, None);
        assert_eq!(ring.take_latest_unconsumed(), Some("Erro B".into()));
        assert_eq!(ring.take_latest_unconsumed(), None);
        assert_eq!(ring.count(), 2);
    }

    #[test]
    fn test_clear() {
        let ring = ErrorRingBuffer::new(5);
        ring.push("Erro".into(), 100, None);
        ring.clear();
        assert_eq!(ring.count(), 0);
        assert_eq!(ring.take_latest_unconsumed(), None);
    }
}
