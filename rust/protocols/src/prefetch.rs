//! Cache de leitura em blocos grandes para fontes de dados remotas (SMB,
//! HTTP) — T6.3/T7.2.
//!
//! O `AVIOContext` do FFmpeg (via `StreamIo` do `ffmpeg-next`) le em
//! pedacos de ~32KB por vez, e o Demuxer faz MUITOS seeks (doc, secoes 6/7:
//! MKV com cues no final do arquivo). Cada leitura/seek numa fonte "crua"
//! (SMB ou HTTP) vira um round-trip de rede. Este modulo amortiza isso: ao
//! ocorrer um cache-miss, le um bloco grande (padrao 4MB, dentro da faixa
//! 2-8MB recomendada pelo doc) de uma vez, e serve as leituras subsequentes
//! dentro desse bloco direto da memoria.
//!
//! Isto e um cache "pull" (so busca o proximo bloco quando o atual acaba ou
//! quando ha um seek para fora dele) — NAO e um read-ahead assincrono em
//! background. Nao ha uma thread separada pre-buscando o PROXIMO bloco
//! enquanto o atual ainda esta sendo consumido. Para leitura sequencial (o
//! caso comum durante playback normal, fora dos seeks pontuais de cues) isso
//! ja reduz bastante o numero de round-trips; para esconder completamente a
//! latencia de rede seria necessario um double-buffer com prefetch em
//! background de verdade, o que ficou fora do escopo desta sessao (ver nota
//! em PHASE-0.1-MVP.md, secao 6).

use std::io::{self, Read, Seek, SeekFrom};

/// Fonte de bytes por offset absoluto — sem cursor proprio, sem estado de
/// "posicao atual". E o unico contrato que SMB e HTTP(S) precisam
/// implementar; a semantica de `Read`/`Seek` (posicao corrente, EOF, etc.)
/// fica inteira no `PrefetchReader`.
pub trait RangeSource: Send {
    /// Le ate `buf.len()` bytes comecando em `offset`. Retorna a quantidade
    /// de bytes efetivamente lidos (pode ser menor que `buf.len()` apenas no
    /// fim do arquivo — 0 significa EOF).
    fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize>;

    /// Tamanho total em bytes, se conhecido de antemao (SMB: retornado no
    /// open; HTTP: Content-Length do probe).
    fn len(&self) -> Option<u64>;
}

/// 4MB — dentro da faixa 2-8MB que o doc recomenda para o buffer de
/// prefetch (secao 6, "Cuidados e Armadilhas").
const DEFAULT_BLOCK_SIZE: usize = 4 * 1024 * 1024;

pub struct PrefetchReader<S: RangeSource> {
    source: S,
    pos: u64,
    block_size: usize,
    cache: Vec<u8>,
    cache_start: u64,
    cache_len: usize,
}

impl<S: RangeSource> PrefetchReader<S> {
    pub fn new(source: S) -> Self {
        Self::with_block_size(source, DEFAULT_BLOCK_SIZE)
    }

    pub fn with_block_size(source: S, block_size: usize) -> Self {
        Self {
            source,
            pos: 0,
            block_size: block_size.max(64 * 1024),
            cache: Vec::new(),
            cache_start: 0,
            cache_len: 0,
        }
    }

    fn cache_hit(&self, pos: u64) -> bool {
        self.cache_len > 0 && pos >= self.cache_start && pos < self.cache_start + self.cache_len as u64
    }

    fn refill(&mut self, pos: u64) -> io::Result<()> {
        if self.cache.len() < self.block_size {
            self.cache.resize(self.block_size, 0);
        }
        let n = self.source.read_range(pos, &mut self.cache[..self.block_size])?;
        self.cache_start = pos;
        self.cache_len = n;
        Ok(())
    }
}

impl<S: RangeSource> Read for PrefetchReader<S> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        if let Some(total) = self.source.len() {
            if self.pos >= total {
                return Ok(0); // EOF
            }
        }
        if !self.cache_hit(self.pos) {
            self.refill(self.pos)?;
            if self.cache_len == 0 {
                return Ok(0); // fonte esgotada de verdade
            }
        }
        let offset_in_cache = (self.pos - self.cache_start) as usize;
        let available = self.cache_len - offset_in_cache;
        let n = available.min(buf.len());
        buf[..n].copy_from_slice(&self.cache[offset_in_cache..offset_in_cache + n]);
        self.pos += n as u64;
        Ok(n)
    }
}

impl<S: RangeSource> Seek for PrefetchReader<S> {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let new_pos: i64 = match pos {
            SeekFrom::Start(p) => p as i64,
            SeekFrom::Current(d) => self.pos as i64 + d,
            SeekFrom::End(d) => {
                let len = self.source.len().ok_or_else(|| {
                    io::Error::new(
                        io::ErrorKind::Unsupported,
                        "tamanho desconhecido: SeekFrom::End nao suportado nesta fonte",
                    )
                })?;
                len as i64 + d
            }
        };
        if new_pos < 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "seek para posicao negativa"));
        }
        self.pos = new_pos as u64;
        Ok(self.pos)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Fonte fake em memoria, so para validar a logica de cache/seek do
    /// PrefetchReader sem precisar de rede de verdade.
    struct MemSource {
        data: Vec<u8>,
        reads: usize,
    }

    impl RangeSource for MemSource {
        fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
            self.reads += 1;
            let offset = offset as usize;
            if offset >= self.data.len() {
                return Ok(0);
            }
            let n = (self.data.len() - offset).min(buf.len());
            buf[..n].copy_from_slice(&self.data[offset..offset + n]);
            Ok(n)
        }
        fn len(&self) -> Option<u64> {
            Some(self.data.len() as u64)
        }
    }

    #[test]
    fn sequential_reads_hit_one_block() {
        let data: Vec<u8> = (0..255u8).cycle().take(10_000).collect();
        let source = MemSource { data: data.clone(), reads: 0 };
        let mut reader = PrefetchReader::with_block_size(source, 4096);

        let mut out = vec![0u8; data.len()];
        let mut total = 0;
        while total < out.len() {
            let n = reader.read(&mut out[total..]).unwrap();
            if n == 0 {
                break;
            }
            total += n;
        }
        assert_eq!(out, data);
        // 10_000 bytes / 4096-byte blocks = 3 refills, bem menos que 10_000
        // reads individuais.
        assert!(reader.source.reads <= 4, "reads de rede: {}", reader.source.reads);
    }

    #[test]
    fn seek_and_read() {
        let data: Vec<u8> = (0..255u8).cycle().take(10_000).collect();
        let source = MemSource { data: data.clone(), reads: 0 };
        let mut reader = PrefetchReader::with_block_size(source, 4096);

        reader.seek(SeekFrom::Start(9000)).unwrap();
        let mut out = [0u8; 500];
        let n = reader.read(&mut out).unwrap();
        assert_eq!(n, 500);
        assert_eq!(&out[..], &data[9000..9500]);
    }
}
