//! Fonte de aleatoriedade compartilhada para o jitter do backoff entre
//! chunks (ver `media_logic::retry_backoff::backoff_with_jitter`, usado por
//! `smb`, `http` e `ftp`). Mesma justificativa do equivalente em
//! `core::playback`: não precisa qualidade criptográfica, só evitar que
//! sessões diferentes se recuperando do mesmo problema de rede retentem
//! exatamente no mesmo instante — e evita puxar a crate `rand`, que não
//! existe em nenhum `Cargo.toml` do workspace hoje.
pub(crate) fn cheap_rand_unit() -> f64 {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    (nanos % 1_000_000_000) as f64 / 1_000_000_000.0
}
