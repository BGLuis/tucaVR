//! Cliente HTTP(S) para playback via URL — T7.1/T7.2.
//!
//! **Achado critico desta sessao**: o `libavformat.so` empacotado neste
//! projeto (`ffmpeg-android-maker`) foi compilado SEM nenhum backend TLS
//! (`CONFIG_MBEDTLS=0`, `CONFIG_GNUTLS=0`, `CONFIG_OPENSSL=0` em
//! `ffmpeg-android-maker/sources/ffmpeg/ffmpeg-8.1.2/config.h`) — logo
//! `CONFIG_HTTPS_PROTOCOL` e `CONFIG_TLS_PROTOCOL` tambem ficaram
//! desabilitados (confirmado em `ffbuild/config.mak`, marcados com `!`, e no
//! `url_protocols[]` gerado em `libavformat/protocol_list.c`: `ff_https_protocol`
//! nao aparece na lista, so `ff_http_protocol`). Ou seja: **`http://` puro
//! funciona nativamente** via `ffmpeg::format::input()` (protocolo `http`
//! registrado, com suporte a range requests do proprio libavformat), mas
//! **`https://` NAO funciona nativamente** — precisa do custom I/O implementado
//! aqui (`HttpsRangeSource` + `crate::prefetch::PrefetchReader`), que faz o
//! TLS inteiramente do lado Rust via `reqwest`/rustls, sem depender de
//! nenhum suporte TLS do FFmpeg. Isso e o oposto do que a tarefa original
//! especulava (que https funcionaria nativamente) — verificado direto no
//! binario, nao assumido.

use crate::prefetch::RangeSource;
use std::io;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct HttpCapabilities {
    pub reachable: bool,
    pub status: u16,
    /// `true` se o servidor aceita range requests (`Accept-Ranges: bytes`,
    /// ou respondeu 206 a um `GET` com `Range`) — se `false`, seek nao vai
    /// funcionar (doc, secao 7, aviso explicito) e a UI deve avisar o
    /// usuario ANTES de comecar a tocar.
    pub seekable: bool,
    pub content_length: Option<u64>,
    pub error: Option<String>,
}

fn base_client() -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        // T7.1 "custom headers": User-Agent fixo por enquanto — a API aqui
        // aceita headers arbitrarios (ver `HttpsRangeSource::new`), mas nao
        // ha UI para o usuario customizar User-Agent/Referer nesta sessao.
        .user_agent("VRMultimediaPlayer/0.1")
        .timeout(Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())
}

fn parse_content_range_total(headers: &reqwest::header::HeaderMap) -> Option<u64> {
    // Content-Range: bytes 0-0/12345
    let v = headers.get(reqwest::header::CONTENT_RANGE)?.to_str().ok()?;
    v.rsplit('/').next()?.parse::<u64>().ok()
}

/// Le o header `Content-Length` diretamente, em vez de usar
/// `reqwest::Response::content_length()`.
///
/// **Bug real encontrado ao escrever os testes desta funcao com um servidor
/// mock** (nunca teria aparecido so lendo o codigo ou testando manualmente
/// sem prestar atencao ao valor retornado): a doc do proprio reqwest diz
/// explicitamente que `content_length()` reflete o tamanho do *corpo da
/// resposta transferido*, nao o header — e uma resposta a HEAD nunca tem
/// corpo. Na pratica isso fazia o branch de HEAD do `probe()` (abaixo)
/// reportar `content_length: Some(0)` para QUALQUER servidor, mesmo um que
/// respondesse corretamente `Content-Length: 123456` no HEAD — o que por
/// sua vez fazia `HttpsRangeSource::read_range` achar que qualquer offset
/// estava "alem do fim do arquivo" e devolver 0 bytes silenciosamente.
fn parse_content_length_header(headers: &reqwest::header::HeaderMap) -> Option<u64> {
    headers
        .get(reqwest::header::CONTENT_LENGTH)?
        .to_str()
        .ok()?
        .parse::<u64>()
        .ok()
}

/// Probe HEAD-based (T7.1): descobre se o servidor suporta range requests e
/// o tamanho do arquivo ANTES de comecar a tocar, para a UI poder avisar
/// que seek nao vai funcionar. Alguns servidores nao aceitam HEAD
/// (405/501); nesse caso cai para um GET com `Range: bytes=0-0` e usa o
/// status/headers da resposta (mais confiavel que o header `Accept-Ranges`
/// sozinho, que alguns servidores omitem mesmo suportando ranges).
pub fn probe(url: &str) -> HttpCapabilities {
    let client = match base_client() {
        Ok(c) => c,
        Err(e) => {
            return HttpCapabilities { reachable: false, status: 0, seekable: false, content_length: None, error: Some(e) };
        }
    };

    if let Ok(resp) = client.head(url).send() {
        if resp.status().is_success() {
            let seekable = resp
                .headers()
                .get(reqwest::header::ACCEPT_RANGES)
                .map(|v| v.to_str().unwrap_or("").eq_ignore_ascii_case("bytes"))
                .unwrap_or(false);
            let content_length = parse_content_length_header(resp.headers());
            return HttpCapabilities { reachable: true, status: resp.status().as_u16(), seekable, content_length, error: None };
        }
    }

    match client.get(url).header(reqwest::header::RANGE, "bytes=0-0").send() {
        Ok(resp) => {
            let status = resp.status().as_u16();
            let seekable = status == 206;
            let content_length = parse_content_range_total(resp.headers())
                .or_else(|| parse_content_length_header(resp.headers()))
                .or_else(|| resp.content_length());
            let reachable = resp.status().is_success() || status == 206;
            HttpCapabilities { reachable, status, seekable, content_length, error: None }
        }
        Err(e) => HttpCapabilities { reachable: false, status: 0, seekable: false, content_length: None, error: Some(e.to_string()) },
    }
}

/// `RangeSource` para HTTPS: cada `read_range` e um `GET` com header `Range`
/// separado (a `PrefetchReader` amortiza isso para blocos de 4MB, entao nao
/// e um request por leitura de 32KB do FFmpeg). Requer que o servidor
/// suporte ranges — sem isso o custom I/O nao teria como fazer seek, entao
/// `new()` falha cedo com uma mensagem clara em vez de deixar o Demuxer
/// travar depois.
pub struct HttpsRangeSource {
    client: reqwest::blocking::Client,
    url: String,
    len: u64,
}

impl HttpsRangeSource {
    pub fn new(url: &str) -> Result<Self, String> {
        let caps = probe(url);
        if !caps.reachable {
            return Err(caps.error.unwrap_or_else(|| format!("URL inacessivel (status {})", caps.status)));
        }
        if !caps.seekable {
            return Err(
                "Servidor nao suporta range requests (Accept-Ranges: bytes) — necessario para o \
                 custom I/O de HTTPS usado neste app, ja que o libavformat empacotado nao tem TLS. \
                 Download progressivo sem seek nao foi implementado (fora do escopo desta sessao)."
                    .to_string(),
            );
        }
        let len = caps
            .content_length
            .ok_or_else(|| "Content-Length desconhecido — nao da para alocar o buffer de leitura".to_string())?;
        let client = base_client()?;
        Ok(Self { client, url: url.to_string(), len })
    }
}

impl RangeSource for HttpsRangeSource {
    fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        if offset >= self.len || buf.is_empty() {
            return Ok(0);
        }
        let end = (offset + buf.len() as u64 - 1).min(self.len - 1);
        let range = format!("bytes={}-{}", offset, end);
        let resp = self
            .client
            .get(&self.url)
            .header(reqwest::header::RANGE, range)
            .send()
            .map_err(|e| io::Error::other(e.to_string()))?;
        let status = resp.status();
        if status.as_u16() != 206 && !status.is_success() {
            return Err(io::Error::other(format!("HTTP {status} ao ler range de {offset}")));
        }
        let bytes = resp.bytes().map_err(|e| io::Error::other(e.to_string()))?;
        let n = bytes.len().min(buf.len());
        buf[..n].copy_from_slice(&bytes[..n]);
        Ok(n)
    }

    fn len(&self) -> Option<u64> {
        Some(self.len)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use httpmock::prelude::*;

    /// Servidor que responde HEAD normalmente e anuncia suporte a range
    /// requests via `Accept-Ranges: bytes` — o caminho feliz mais comum
    /// (ex: Nginx/Apache/S3 servindo um arquivo estatico).
    #[test]
    fn probe_detects_seekable_via_head() {
        let server = MockServer::start();
        let mock = server.mock(|when, then| {
            when.method("HEAD").path("/video.mp4");
            then.status(200)
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", "123456");
        });

        let caps = probe(&server.url("/video.mp4"));

        mock.assert();
        assert!(caps.reachable);
        assert!(caps.seekable);
        assert_eq!(caps.content_length, Some(123456));
        assert_eq!(caps.status, 200);
    }

    /// Servidor que responde 200 no HEAD mas sem `Accept-Ranges` — deve ser
    /// reportado como nao-seekable (a UI avisa o usuario, T7.1).
    #[test]
    fn probe_detects_non_seekable_when_no_accept_ranges_header() {
        let server = MockServer::start();
        server.mock(|when, then| {
            when.method("HEAD").path("/video.mp4");
            then.status(200).header("Content-Length", "999");
        });

        let caps = probe(&server.url("/video.mp4"));

        assert!(caps.reachable);
        assert!(!caps.seekable);
    }

    /// Alguns servidores nao aceitam HEAD (405/501) — o probe deve cair
    /// para GET com `Range: bytes=0-0` e usar o status 206 como sinal de
    /// que ranges sao suportados (ver doc do `probe`).
    #[test]
    fn probe_falls_back_to_range_get_when_head_not_allowed() {
        let server = MockServer::start();
        server.mock(|when, then| {
            when.method("HEAD").path("/video.mp4");
            then.status(405);
        });
        server.mock(|when, then| {
            when.method("GET")
                .path("/video.mp4")
                .header("Range", "bytes=0-0");
            then.status(206)
                .header("Content-Range", "bytes 0-0/5000");
        });

        let caps = probe(&server.url("/video.mp4"));

        assert!(caps.reachable);
        assert!(caps.seekable);
        assert_eq!(caps.content_length, Some(5000));
        assert_eq!(caps.status, 206);
    }

    /// Servidor inalcancavel (porta fechada) — probe nao deve entrar em
    /// pânico, so reportar `reachable: false`.
    #[test]
    fn probe_reports_unreachable_server() {
        // Porta improvavel de estar escutando algo neste host de CI/dev.
        let caps = probe("http://127.0.0.1:1/video.mp4");

        assert!(!caps.reachable);
        assert!(!caps.seekable);
    }

    #[test]
    fn https_range_source_fails_fast_when_server_lacks_range_support() {
        let server = MockServer::start();
        server.mock(|when, then| {
            when.method("HEAD").path("/video.mp4");
            then.status(200).header("Content-Length", "1000");
            // Sem Accept-Ranges: bytes.
        });

        let result = HttpsRangeSource::new(&server.url("/video.mp4"));

        match result {
            Err(msg) => assert!(msg.contains("range"), "unexpected error message: {msg}"),
            Ok(_) => panic!("expected HttpsRangeSource::new to fail without range support"),
        }
    }

    #[test]
    fn https_range_source_reads_requested_byte_range() {
        let server = MockServer::start();
        server.mock(|when, then| {
            when.method("HEAD").path("/video.mp4");
            then.status(200)
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", "10");
        });
        server.mock(|when, then| {
            when.method("GET")
                .path("/video.mp4")
                .header("Range", "bytes=2-5");
            then.status(206).body(b"CDEF".to_vec());
        });

        let mut source = HttpsRangeSource::new(&server.url("/video.mp4")).unwrap();
        let mut buf = [0u8; 4];
        let n = source.read_range(2, &mut buf).unwrap();

        assert_eq!(n, 4);
        assert_eq!(&buf, b"CDEF");
    }

    #[test]
    fn https_range_source_read_past_end_returns_zero() {
        let server = MockServer::start();
        server.mock(|when, then| {
            when.method("HEAD").path("/video.mp4");
            then.status(200)
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", "10");
        });

        let mut source = HttpsRangeSource::new(&server.url("/video.mp4")).unwrap();
        let mut buf = [0u8; 4];
        let n = source.read_range(10, &mut buf).unwrap();

        assert_eq!(n, 0);
    }
}
