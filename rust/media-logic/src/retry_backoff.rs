//! Backoff exponencial com jitter ("equal jitter": metade fixa + metade
//! aleatória) para as retentativas de leitura/rede espalhadas pelo
//! workspace — hoje `core::playback` usa um delay FIXO de 400ms entre
//! tentativas (`READ_ERROR_RETRY_BACKOFF`) e o retry por chunk do SFTP
//! (`rust/protocols/src/sftp/mod.rs`) também não tem jitter. Sem jitter,
//! múltiplas sessões/streams se recuperando do mesmo problema de rede tendem
//! a re-tentar em uníssono (thundering herd) — jitter espalha as tentativas
//! no tempo.
//!
//! `rand_unit` é injetado (não puxa a crate `rand`, que não existe em nenhum
//! `Cargo.toml` do workspace hoje) — no ponto de chamada em `core`/
//! `protocols` basta derivar um valor "bom o suficiente" de
//! `Instant::now().elapsed().subsec_nanos()` normalizado para `[0, 1)`; não
//! precisa qualidade criptográfica, só evitar sincronismo entre tentativas.
//!
//! Zero I/O — testável com `cargo test -p media-logic`.

use std::time::Duration;

/// `attempt` começa em 1 (primeira retentativa). `rand_unit` deve estar em
/// `[0.0, 1.0]` — valores fora da faixa são grampeados, nunca geram pânico.
pub fn backoff_with_jitter(attempt: u32, base: Duration, cap: Duration, rand_unit: f64) -> Duration {
    let exp_attempt = attempt.saturating_sub(1).min(31);
    let exponential = base.saturating_mul(1u32 << exp_attempt).min(cap);
    let half = exponential / 2;
    let jitter = half.mul_f64(rand_unit.clamp(0.0, 1.0));
    half + jitter
}

#[cfg(test)]
mod tests {
    use super::*;

    const BASE: Duration = Duration::from_millis(400);
    const CAP: Duration = Duration::from_secs(5);

    #[test]
    fn first_attempt_uses_the_base_duration() {
        let exp_only = backoff_with_jitter(1, BASE, CAP, 1.0);
        assert_eq!(exp_only, BASE);
    }

    #[test]
    fn grows_exponentially_with_attempt_number() {
        let a1 = backoff_with_jitter(1, BASE, CAP, 1.0);
        let a2 = backoff_with_jitter(2, BASE, CAP, 1.0);
        let a3 = backoff_with_jitter(3, BASE, CAP, 1.0);
        assert_eq!(a2, a1 * 2);
        assert_eq!(a3, a1 * 4);
    }

    #[test]
    fn is_capped_at_high_attempt_counts() {
        let huge = backoff_with_jitter(50, BASE, CAP, 1.0);
        assert_eq!(huge, CAP);
    }

    #[test]
    fn zero_jitter_returns_exactly_half_the_exponential_delay() {
        let d = backoff_with_jitter(3, BASE, CAP, 0.0);
        let exponential = (BASE * 4).min(CAP);
        assert_eq!(d, exponential / 2);
    }

    #[test]
    fn full_jitter_returns_exactly_the_full_exponential_delay() {
        let d = backoff_with_jitter(3, BASE, CAP, 1.0);
        let exponential = (BASE * 4).min(CAP);
        assert_eq!(d, exponential);
    }

    #[test]
    fn out_of_range_rand_unit_is_clamped_not_panicking() {
        let below = backoff_with_jitter(2, BASE, CAP, -3.0);
        let above = backoff_with_jitter(2, BASE, CAP, 99.0);
        assert_eq!(below, backoff_with_jitter(2, BASE, CAP, 0.0));
        assert_eq!(above, backoff_with_jitter(2, BASE, CAP, 1.0));
    }

    #[test]
    fn different_rand_units_at_the_same_attempt_vary_the_delay() {
        let low = backoff_with_jitter(4, BASE, CAP, 0.1);
        let high = backoff_with_jitter(4, BASE, CAP, 0.9);
        assert!(high > low, "jitter maior deveria produzir delay maior");
    }
}
