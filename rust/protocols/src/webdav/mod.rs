//! Cliente WebDAV para navegação de diretórios (PROPFIND) e streaming com seek (Range requests) — T3.1 / T3.2.
//!
//! WebDAV é uma extensão do HTTP (RFC 4918). A reprodução de arquivos opera via HTTP GET
//! com cabeçalhos `Range: bytes=start-end` paralelizados em sub-blocos concorrentes,
//! aproveitando o pool de conexões do `reqwest::blocking::Client`. A listagem de pastas utiliza
//! o método `PROPFIND` com cabeçalho `Depth: 1` e parser incremental XML com `quick-xml` em
//! modo streaming para manter o consumo de memória estável mesmo em pastas com milhares de itens.

pub mod uri;

pub use uri::{is_webdav_uri, redact, WebdavTarget};

use crate::chunking::split_range;
use crate::prefetch::RangeSource;
use quick_xml::events::Event;
use quick_xml::reader::Reader;
use std::io;
use std::time::Duration;

/// Tamanho de cada sub-leitura concorrente dentro de um `read_range` (2 MB).
const WEBDAV_CONCURRENT_CHUNK_SIZE: u32 = 2 * 1024 * 1024;

/// Número máximo de sub-requisições concorrentes por bloco de leitura.
const WEBDAV_MAX_CONCURRENT_CHUNKS: usize = 4;

/// Payload XML padrão para PROPFIND solicitando metadados básicos.
const PROPFIND_REQUEST_XML: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
    <D:prop>
        <D:resourcetype/>
        <D:getcontentlength/>
        <D:getlastmodified/>
        <D:displayname/>
    </D:prop>
</D:propfind>"#;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WebdavDirEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
}

/// Cria um cliente HTTP `reqwest::blocking::Client` com as configurações do `WebdavTarget`.
pub fn create_client(target: &WebdavTarget) -> Result<reqwest::blocking::Client, String> {
    let mut builder = reqwest::blocking::Client::builder()
        .user_agent("VRMultimediaPlayer/0.1")
        .timeout(Duration::from_secs(10));

    if target.accept_invalid_certs {
        builder = builder.danger_accept_invalid_certs(true);
    }

    builder.build().map_err(|e| e.to_string())
}

fn local_name(qname: &[u8]) -> &[u8] {
    match qname.iter().rposition(|&b| b == b':') {
        Some(pos) => &qname[pos + 1..],
        None => qname,
    }
}

fn normalize_path_for_compare(path: &str) -> String {
    let p = if let Some(idx) = path.find("://") {
        let after_scheme = &path[idx + 3..];
        match after_scheme.find('/') {
            Some(slash) => &after_scheme[slash..],
            None => "/",
        }
    } else {
        path
    };

    let trimmed = p.trim_matches('/');
    percent_encoding::percent_decode_str(trimmed)
        .decode_utf8_lossy()
        .to_string()
}

/// Faz o parse da resposta XML multistatus de um PROPFIND em modo streaming via `quick-xml`.
pub fn parse_multistatus_xml<R: io::BufRead>(
    reader: R,
    requested_url_path: &str,
) -> Result<Vec<WebdavDirEntry>, String> {
    let mut xml_reader = Reader::from_reader(reader);
    xml_reader.config_mut().trim_text(true);

    let mut entries = Vec::new();

    let mut in_response = false;
    let mut in_propstat = false;
    let mut in_resourcetype = false;
    let mut current_tag = Vec::new();

    let mut current_href = String::new();
    let mut current_display_name: Option<String> = None;
    let mut current_content_length: Option<u64> = None;
    let mut is_collection = false;
    let mut current_status = String::new();

    let mut buf = Vec::new();
    let norm_requested = normalize_path_for_compare(requested_url_path);

    loop {
        match xml_reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let local = local_name(e.name().as_ref()).to_vec();
                current_tag = local.clone();

                if local.eq_ignore_ascii_case(b"response") {
                    in_response = true;
                    in_propstat = false;
                    in_resourcetype = false;
                    current_href.clear();
                    current_display_name = None;
                    current_content_length = None;
                    is_collection = false;
                    current_status.clear();
                } else if local.eq_ignore_ascii_case(b"propstat") {
                    in_propstat = true;
                } else if local.eq_ignore_ascii_case(b"resourcetype") {
                    in_resourcetype = true;
                } else if in_resourcetype && local.eq_ignore_ascii_case(b"collection") {
                    is_collection = true;
                }
            }
            Ok(Event::Empty(ref e)) => {
                let name = e.name();
                let local = local_name(name.as_ref());
                if in_resourcetype && local.eq_ignore_ascii_case(b"collection") {
                    is_collection = true;
                }
            }
            Ok(Event::Text(ref e)) => {
                let text = match e.unescape() {
                    Ok(t) => t.to_string(),
                    Err(_) => String::from_utf8_lossy(e.as_ref()).to_string(),
                };

                if current_tag.eq_ignore_ascii_case(b"href") && in_response && !in_propstat {
                    current_href.push_str(&text);
                } else if current_tag.eq_ignore_ascii_case(b"displayname") && in_propstat {
                    current_display_name = Some(text);
                } else if current_tag.eq_ignore_ascii_case(b"getcontentlength") && in_propstat {
                    if let Ok(len) = text.trim().parse::<u64>() {
                        current_content_length = Some(len);
                    }
                } else if current_tag.eq_ignore_ascii_case(b"status") && in_propstat {
                    current_status = text;
                }
            }
            Ok(Event::End(ref e)) => {
                let name = e.name();
                let local = local_name(name.as_ref());
                current_tag.clear();

                if local.eq_ignore_ascii_case(b"resourcetype") {
                    in_resourcetype = false;
                } else if local.eq_ignore_ascii_case(b"propstat") {
                    in_propstat = false;
                } else if local.eq_ignore_ascii_case(b"response") && in_response {
                    in_response = false;

                    let href_norm = normalize_path_for_compare(&current_href);

                    // Ignora o elemento que referencia a própria pasta consultada
                    if href_norm != norm_requested {
                        let last_segment = href_norm.rsplit('/').next().unwrap_or(&href_norm);
                        let decoded_name = percent_encoding::percent_decode_str(last_segment)
                            .decode_utf8_lossy()
                            .to_string();

                        let name = if let Some(ref disp) = current_display_name {
                            if !disp.trim().is_empty() {
                                disp.trim().to_string()
                            } else {
                                decoded_name
                            }
                        } else {
                            decoded_name
                        };

                        if !name.is_empty() {
                            let size = if is_collection {
                                0
                            } else {
                                current_content_length.unwrap_or(0)
                            };

                            entries.push(WebdavDirEntry {
                                name,
                                is_dir: is_collection,
                                size,
                            });
                        }
                    }
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => return Err(format!("Erro de parse XML WebDAV: {e}")),
            _ => {}
        }
        buf.clear();
    }

    Ok(entries)
}

/// Lista o conteúdo de um diretório em um servidor WebDAV via PROPFIND Depth: 1.
pub fn list_directory(target: &WebdavTarget, dir_path: &str) -> Result<Vec<WebdavDirEntry>, String> {
    let client = create_client(target)?;
    list_directory_with_client(&client, target, dir_path)
}

// Extraida de list_directory pra ser reusada por scan_has_media, que reusa o MESMO
// reqwest::blocking::Client (e seu pool de conexoes HTTP keep-alive) pra varias
// pastas, em vez de criar um cliente novo por nivel.
fn list_directory_with_client(
    client: &reqwest::blocking::Client,
    target: &WebdavTarget,
    dir_path: &str,
) -> Result<Vec<WebdavDirEntry>, String> {
    let mut url = target.url_for_path(dir_path);

    // Servidores WebDAV frequentemente exigem que a URL de uma coleção termine com '/'
    if !url.ends_with('/') {
        url.push('/');
    }

    let propfind_method = reqwest::Method::from_bytes(b"PROPFIND")
        .map_err(|e| format!("Método HTTP inválido: {e}"))?;

    let mut req = client
        .request(propfind_method, &url)
        .header("Depth", "1")
        .header("Content-Type", "application/xml; charset=utf-8")
        .body(PROPFIND_REQUEST_XML);

    if !target.username.is_empty() {
        req = req.basic_auth(&target.username, Some(&target.password));
    }

    let resp = req
        .send()
        .map_err(|e| format!("Falha na conexão WebDAV com {}: {e}", target.host))?;

    let status = resp.status();
    if status.as_u16() != 207 && !status.is_success() {
        return Err(format!("Servidor WebDAV respondeu com status HTTP {status}"));
    }

    let reader = io::BufReader::new(resp);
    parse_multistatus_xml(reader, &url)
}

/// Varredura recursiva "esta pasta tem alguma midia reproduzivel?" -- ver
/// `crate::folder_scan`. Reusa o MESMO cliente HTTP (pool de conexoes
/// keep-alive) pra todas as subpastas visitadas, pilha explicita, sem
/// limite de profundidade fixo, para no primeiro arquivo de midia ou no
/// deadline de seguranca.
pub fn scan_has_media(target: &WebdavTarget, dir_path: &str) -> Result<crate::folder_scan::ScanResult, String> {
    let client = create_client(target)?;
    let deadline = crate::folder_scan::deadline_from_now();

    let mut stack = vec![dir_path.to_string()];
    let mut result = crate::folder_scan::ScanResult::exhausted_empty();

    while let Some(current) = stack.pop() {
        if std::time::Instant::now() > deadline {
            result = crate::folder_scan::ScanResult::timed_out_assume_has_media();
            break;
        }
        let entries = match list_directory_with_client(&client, target, &current) {
            Ok(e) => e,
            Err(_) => continue,
        };
        let mut found = false;
        for e in &entries {
            if e.is_dir {
                let child = if current.is_empty() {
                    e.name.clone()
                } else {
                    format!("{}/{}", current.trim_end_matches('/'), e.name)
                };
                stack.push(child);
            } else if crate::folder_scan::is_media_filename(&e.name) {
                found = true;
                break;
            }
        }
        if found {
            result = crate::folder_scan::ScanResult::found();
            break;
        }
    }

    Ok(result)
}

fn parse_content_range_total(headers: &reqwest::header::HeaderMap) -> Option<u64> {
    // Content-Range: bytes 0-0/12345
    let v = headers.get(reqwest::header::CONTENT_RANGE)?.to_str().ok()?;
    v.rsplit('/').next()?.parse::<u64>().ok()
}

fn parse_content_length_header(headers: &reqwest::header::HeaderMap) -> Option<u64> {
    headers
        .get(reqwest::header::CONTENT_LENGTH)?
        .to_str()
        .ok()?
        .parse::<u64>()
        .ok()
}

fn probe_file(
    client: &reqwest::blocking::Client,
    url: &str,
    target: &WebdavTarget,
) -> Result<u64, String> {
    // Tentativa 1: HEAD
    let mut head_req = client.head(url);
    if !target.username.is_empty() {
        head_req = head_req.basic_auth(&target.username, Some(&target.password));
    }

    if let Ok(resp) = head_req.send()
        && resp.status().is_success()
        && let Some(len) = parse_content_length_header(resp.headers())
    {
        return Ok(len);
    }

    // Tentativa 2: GET Range bytes=0-0 (mais confiável em WebDAV)
    let mut get_req = client.get(url).header(reqwest::header::RANGE, "bytes=0-0");
    if !target.username.is_empty() {
        get_req = get_req.basic_auth(&target.username, Some(&target.password));
    }

    match get_req.send() {
        Ok(resp) => {
            let status = resp.status().as_u16();
            if status == 206 || resp.status().is_success() {
                let len = parse_content_range_total(resp.headers())
                    .or_else(|| parse_content_length_header(resp.headers()))
                    .or_else(|| resp.content_length());

                len.ok_or_else(|| {
                    "Content-Length desconhecido — o servidor WebDAV não informou o tamanho do arquivo"
                        .to_string()
                })
            } else {
                Err(format!("Falha ao inspecionar arquivo: HTTP {status}"))
            }
        }
        Err(e) => Err(format!("Falha ao conectar para probe WebDAV: {e}")),
    }
}

/// Fonte de leitura remota via WebDAV que implementa `RangeSource` para o demuxer.
pub struct WebdavFileSource {
    client: reqwest::blocking::Client,
    url: String,
    username: String,
    password: String,
    len: u64,
}

impl WebdavFileSource {
    pub fn open(target: &WebdavTarget) -> Result<Self, String> {
        let client = create_client(target)?;
        let url = target.file_url();
        let len = probe_file(&client, &url, target)?;

        Ok(Self {
            client,
            url,
            username: target.username.clone(),
            password: target.password.clone(),
            len,
        })
    }
}

fn fetch_webdav_range(
    client: &reqwest::blocking::Client,
    url: &str,
    username: &str,
    password: &str,
    offset: u64,
    len: u32,
) -> io::Result<Vec<u8>> {
    let end = offset + len as u64 - 1;
    let range = format!("bytes={offset}-{end}");

    let mut req = client.get(url).header(reqwest::header::RANGE, range);
    if !username.is_empty() {
        req = req.basic_auth(username, Some(password));
    }

    let resp = req
        .send()
        .map_err(|e| io::Error::other(format!("Erro ao buscar range WebDAV: {e}")))?;

    let status = resp.status();
    if status.as_u16() != 206 && !status.is_success() {
        return Err(io::Error::other(format!(
            "HTTP {status} ao ler range [{offset}..{end}]"
        )));
    }

    resp.bytes()
        .map(|b| b.to_vec())
        .map_err(|e| io::Error::other(e.to_string()))
}

impl RangeSource for WebdavFileSource {
    fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        if offset >= self.len || buf.is_empty() {
            return Ok(0);
        }

        let want = ((self.len - offset).min(buf.len() as u64)) as u32;
        let chunks = split_range(offset, want, WEBDAV_CONCURRENT_CHUNK_SIZE);

        let mut total = 0usize;
        'batches: for batch in chunks.chunks(WEBDAV_MAX_CONCURRENT_CHUNKS) {
            let mut results: Vec<io::Result<Vec<u8>>> = Vec::with_capacity(batch.len());
            std::thread::scope(|scope| {
                let handles: Vec<_> = batch
                    .iter()
                    .map(|&(chunk_offset, chunk_len)| {
                        let client = self.client.clone();
                        let url = self.url.clone();
                        let user = self.username.clone();
                        let pass = self.password.clone();
                        scope.spawn(move || {
                            fetch_webdav_range(&client, &url, &user, &pass, chunk_offset, chunk_len)
                        })
                    })
                    .collect();

                for handle in handles {
                    results.push(handle.join().unwrap_or_else(|_| {
                        Err(io::Error::other("thread de leitura WebDAV entrou em pânico"))
                    }));
                }
            });

            for (idx, result) in results.into_iter().enumerate() {
                let data = result?;
                let (chunk_offset, chunk_len) = batch[idx];
                let start_in_buf = (chunk_offset - offset) as usize;
                let n = data.len();
                buf[start_in_buf..start_in_buf + n].copy_from_slice(&data[..n]);
                total += n;
                if n < chunk_len as usize {
                    break 'batches;
                }
            }
        }

        Ok(total)
    }

    fn len(&self) -> Option<u64> {
        Some(self.len)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn parse_multistatus_nextcloud_sample() {
        let xml = r#"<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
  <d:response>
    <d:href>/remote.php/dav/files/user/Videos/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/Videos/VR%20180/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/Videos/filme_final%20(3D).mp4</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype/>
        <d:getcontentlength>12345678</d:getcontentlength>
        <d:displayname>filme_final (3D).mp4</d:displayname>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"#;

        let entries = parse_multistatus_xml(Cursor::new(xml), "/remote.php/dav/files/user/Videos/").unwrap();
        assert_eq!(entries.len(), 2);

        // Subpasta VR 180
        assert_eq!(entries[0].name, "VR 180");
        assert!(entries[0].is_dir);
        assert_eq!(entries[0].size, 0);

        // Arquivo de vídeo
        assert_eq!(entries[1].name, "filme_final (3D).mp4");
        assert!(!entries[1].is_dir);
        assert_eq!(entries[1].size, 12345678);
    }

    #[test]
    fn parse_multistatus_with_full_urls() {
        let xml = r#"<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>http://192.168.1.50:5005/webdav/</D:href>
    <D:propstat>
      <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>http://192.168.1.50:5005/webdav/sample.mkv</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype/>
        <D:getcontentlength>54321</D:getcontentlength>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"#;

        let entries = parse_multistatus_xml(Cursor::new(xml), "http://192.168.1.50:5005/webdav").unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].name, "sample.mkv");
        assert!(!entries[0].is_dir);
        assert_eq!(entries[0].size, 54321);
    }

    #[test]
    fn list_directory_with_mock_server() {
        use httpmock::prelude::*;

        let server = MockServer::start();
        let xml_response = r#"<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/media/</D:href>
    <D:propstat>
      <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/media/clip.mp4</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype/>
        <D:getcontentlength>99999</D:getcontentlength>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"#;

        let mock = server.mock(|when, then| {
            when.is_true(|req| req.method().as_str() == "PROPFIND")
                .path("/media/")
                .header("Depth", "1");
            then.status(207)
                .header("Content-Type", "application/xml")
                .body(xml_response);
        });

        let target = WebdavTarget {
            host: server.host(),
            port: server.port(),
            base_path: "/media".into(),
            file_path: "".into(),
            username: "".into(),
            password: "".into(),
            use_https: false,
            accept_invalid_certs: false,
        };

        let entries = list_directory(&target, "").unwrap();
        mock.assert();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].name, "clip.mp4");
        assert_eq!(entries[0].size, 99999);
    }

    #[test]
    fn webdav_file_source_reads_range() {
        use httpmock::prelude::*;

        let server = MockServer::start();

        server.mock(|when, then| {
            when.method("HEAD").path("/files/movie.mp4");
            then.status(200)
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", "1024");
        });

        server.mock(|when, then| {
            when.method("GET")
                .path("/files/movie.mp4")
                .header("Range", "bytes=0-9");
            then.status(206)
                .header("Content-Range", "bytes 0-9/1024")
                .body(b"0123456789");
        });

        let target = WebdavTarget {
            host: server.host(),
            port: server.port(),
            base_path: "/files".into(),
            file_path: "movie.mp4".into(),
            username: "".into(),
            password: "".into(),
            use_https: false,
            accept_invalid_certs: false,
        };

        let mut source = WebdavFileSource::open(&target).unwrap();
        assert_eq!(source.len(), Some(1024));

        let mut buf = [0u8; 10];
        let bytes_read = source.read_range(0, &mut buf).unwrap();
        assert_eq!(bytes_read, 10);
        assert_eq!(&buf, b"0123456789");
    }
}
