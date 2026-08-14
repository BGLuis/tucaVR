//! Divisao pura de um range de bytes em sub-chunks para busca CONCORRENTE —
//! usado por `smb::SmbFileSource`, `sftp::SftpFileSource` e
//! `http::HttpsRangeSource` (T6.3/T7.2, otimizacao desta sessao).
//!
//! `smb2::client::stream::FileReader::read_at` e
//! `russh_sftp::client::RawSftpSession::read` tomam `&self` (nao `&mut
//! self`) especificamente para permitir chamadas posicionais concorrentes
//! multiplexadas sobre a MESMA sessao (confirmado lendo o codigo-fonte das
//! duas crates: SMB2 por `MessageId`, SFTP por request-id via `DashMap` +
//! `oneshot`) — mas o codigo deste projeto, antes desta sessao, so disparava
//! uma leitura de cada vez dentro do bloco de 4-12MB que o `PrefetchReader`
//! pede. Este modulo e o pedaco puro/determinístico dessa mudanca: decidir
//! EM QUANTOS pedacos, e de que tamanho, dividir um pedido grande antes de
//! disparar as leituras de verdade (que sao I/O de rede de verdade, entao
//! ficam nos modulos de cada protocolo, nao aqui).
//!
//! Nenhuma dependencia de rede/Android — roda em `cargo test` puro.

/// Divide `[offset, offset + total_len)` em sub-ranges contiguos e
/// nao-sobrepostos de no maximo `chunk_size` bytes cada, na ordem em que
/// aparecem no arquivo. Cada item retornado e `(offset_absoluto,
/// tamanho_do_chunk)`.
///
/// `total_len == 0` ou `chunk_size == 0` devolvem uma lista vazia (nada a
/// pedir / tamanho de chunk invalido — quem chama decide como tratar).
pub fn split_range(offset: u64, total_len: u32, chunk_size: u32) -> Vec<(u64, u32)> {
    if total_len == 0 || chunk_size == 0 {
        return Vec::new();
    }

    let mut chunks = Vec::with_capacity((total_len as usize).div_ceil(chunk_size as usize));
    let mut remaining = total_len;
    let mut cursor = offset;
    while remaining > 0 {
        let this_chunk = remaining.min(chunk_size);
        chunks.push((cursor, this_chunk));
        cursor += this_chunk as u64;
        remaining -= this_chunk;
    }
    chunks
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_len_returns_no_chunks() {
        assert_eq!(split_range(1000, 0, 256), Vec::new());
    }

    #[test]
    fn zero_chunk_size_returns_no_chunks() {
        assert_eq!(split_range(0, 1000, 0), Vec::new());
    }

    #[test]
    fn smaller_than_chunk_size_returns_single_chunk() {
        assert_eq!(split_range(500, 100, 256), vec![(500, 100)]);
    }

    #[test]
    fn exact_multiple_returns_equal_chunks() {
        assert_eq!(split_range(0, 768, 256), vec![(0, 256), (256, 256), (512, 256)]);
    }

    #[test]
    fn remainder_yields_smaller_final_chunk() {
        assert_eq!(split_range(0, 700, 256), vec![(0, 256), (256, 256), (512, 188)]);
    }

    #[test]
    fn chunks_are_contiguous_and_cover_the_full_range_exactly() {
        let offset = 12_345u64;
        let total_len = 10_000_003u32;
        let chunk_size = 262_144u32; // proximo do SFTP_MAX_READ_CHUNK real

        let chunks = split_range(offset, total_len, chunk_size);

        let mut expected_next = offset;
        let mut covered = 0u64;
        for &(start, len) in &chunks {
            assert_eq!(start, expected_next, "chunk nao contiguo: esperava comecar em {expected_next}, veio {start}");
            assert!(len > 0 && len <= chunk_size, "chunk fora do tamanho maximo: {len}");
            expected_next += len as u64;
            covered += len as u64;
        }
        assert_eq!(covered, total_len as u64, "chunks nao cobrem o range inteiro");
    }

    #[test]
    fn single_byte_range() {
        assert_eq!(split_range(42, 1, 4096), vec![(42, 1)]);
    }
}
