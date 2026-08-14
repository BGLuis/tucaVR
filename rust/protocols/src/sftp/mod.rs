//! Cliente SFTP (T6.2) construido sobre `russh` + `russh-sftp` — puro Rust,
//! sem `libssh2`/OpenSSL nativos (a armadilha de cross-compile que o doc,
//! secao 6, cita explicitamente para a crate `ssh2`). Igual a `crate::smb`:
//! `russh`/`russh-sftp` sao inteiramente `async`/tokio, mas o FFmpeg custom
//! I/O exige callbacks SINCRONOS rodando na thread que o chama. Cada chamada
//! de listagem ou `SftpFileSource` cria seu proprio `tokio::runtime::Runtime`
//! (current-thread) e usa `block_on` para ligar as duas pontas — nao ha um
//! runtime global compartilhado, mesmo padrao do SMB.
//!
//! ## Leitura posicional de verdade (T6.3)
//!
//! Ao contrario de FTP (`crate::ftp`, que precisa da dança `REST`+`RETR` e
//! reabrir a conexao a cada seek "de verdade" — SFTP e literalmente a
//! vantagem de "random access nativo" que o doc (secao 6, T6.3) atribui a
//! este protocolo em contraste com FTP), o pacote `SSH_FXP_READ` do
//! protocolo SFTP ja carrega um `offset` explicito — e um verbo "pread", nao
//! "read sequencial com cursor". `SftpFileSource::try_read_range` usa
//! `RawSftpSession::read(handle, offset, len)` diretamente por isso, em vez
//! do `File` de alto nivel que `SftpSession::open` devolve (que mantem um
//! cursor `pos` interno e exigiria um `seek` explicito antes de cada read —
//! round-trip extra que o pread nativo dispensa). Isso elimina de vez
//! qualquer necessidade de rastrear "a posicao atual do handle" e comparar
//! com o offset pedido antes de decidir se um seek e necessario: cada
//! `read_range` e sempre um pedido posicional novo.
//!
//! `try_read_range` divide o buffer pedido em sub-chunks de ate
//! `SFTP_MAX_READ_CHUNK` bytes cada (`crate::chunking::split_range`) — em vez
//! de confiar que o servidor devolve o buffer inteiro numa unica resposta,
//! ja que servidores SFTP tem permissao pelo protocolo de truncar uma
//! leitura maior que o `max_packet_len` negociado — e dispara todos os
//! `SSH_FXP_READ`s CONCORRENTEMENTE (T6.3, otimizacao desta sessao: antes
//! disto, o laco era sequencial, uma leitura de cada vez, apesar de
//! `RawSftpSession::read` tomar `&self` exatamente para permitir chamadas
//! concorrentes multiplexadas por request-id sobre a mesma sessao SSH — essa
//! serializacao artificial era o principal suspeito da degradacao de
//! throughput observada em streaming 8K60 via SFTP). Os resultados sao
//! remontados na ordem original; um `StatusCode::Eof`/short-read no meio do
//! range para a remontagem ali (o resto, se buscado, e descartado).
//!
//! ## Verificacao de host key: aceita qualquer uma, DE PROPOSITO
//!
//! `SftpHandler::check_server_key` sempre retorna `Ok(true)` — ou seja, NAO
//! ha verificacao de host key (equivalente a
//! `ssh -o StrictHostKeyChecking=no`, vulneravel a
//! man-in-the-middle-na-rede-local). Decisao deliberada e documentada, nao
//! um descuido: nao existe infraestrutura de `known_hosts`/TOFU nesta app
//! (nem para SMB nem para NFS — mesmo padrao ja aceito no doc para "sem
//! infra de auth real para NAS domestico"), e o caso de uso e uma rede
//! Wi-Fi domestica onde o Quest 3 fala com um NAS/servidor SFTP na mesma
//! LAN. Se isto algum dia mudar (uso fora de uma LAN confiavel), a
//! mitigacao correta e fixacao de chave (guardar o fingerprint na primeira
//! conexao e comparar nas seguintes), nao "confiar em tudo" como agora.
//!
//! ## Keepalive e reconexao (T6.4, doc secao 6 `[!NOTE]` "Timeout agressivo")
//!
//! `russh::client::Config` tem `keepalive_interval`/`keepalive_max` nativos
//! (envia mensagens sem resposta esperada em intervalo, mesma ideia do
//! `ServerAliveInterval` do OpenSSH) — usado aqui em vez de reimplementar
//! isso na mao. Isso ajuda a manter a conexao viva e a detectar quedas mais
//! cedo, mas `russh` NAO tem um `auto_reconnect` embutido como a crate
//! `smb2` (ver `smb/mod.rs`). A mitigacao aqui e a mesma ideia sugerida para
//! FTP no doc: qualquer erro de I/O durante `read_range` dispara UMA
//! reconexao completa (nova sessao SSH + reabre o handle do arquivo) seguida
//! de uma unica nova tentativa da MESMA leitura; se essa tambem falhar, o
//! erro sobe para o chamador (`Demuxer`).
//!
//! **O que NAO esta implementado** (fora do escopo desta sessao, documentado
//! honestamente em vez de fingido): escrita de arquivos, `known_hosts`,
//! agentes SSH (`ssh-agent`), certificados OpenSSH. Autenticacao por chave
//! privada (T6.2 "avancado") esta implementada (`SftpTarget::private_key`,
//! PEM em memoria — ver `uri.rs`), password auth e o caminho principal.

pub mod uri;

pub use uri::{SftpTarget, is_sftp_uri, redact};

use crate::chunking::split_range;
use crate::prefetch::RangeSource;
use futures_util::future::join_all;
use russh::client::{self, Handler};
use russh::keys::key::PublicKey;
use russh::Disconnect;
use russh_sftp::client::error::Error as SftpError;
use russh_sftp::client::{RawSftpSession, SftpSession};
use russh_sftp::protocol::{FileAttributes, OpenFlags, StatusCode};
use std::io;
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Runtime;

fn new_runtime() -> io::Result<Runtime> {
    tokio::runtime::Builder::new_current_thread().enable_all().build()
}

/// Ate quantos bytes pedir num unico `SSH_FXP_READ` — um pouco abaixo do
/// `max_packet_len` default de `russh_sftp` (256KiB) pra sobrar folga de
/// overhead de protocolo. Ver doc do modulo sobre o laco de leitura.
const SFTP_MAX_READ_CHUNK: u32 = 256 * 1024 - 4096;

/// Handler minimo exigido pelo `russh::client` para receber eventos da
/// sessao SSH (banner, checagem de host key, etc). `check_server_key`
/// aceita QUALQUER chave — ver `[!WARNING]` no doc do modulo. Todos os
/// outros callbacks usam a implementacao default do trait.
struct SftpHandler;

#[async_trait::async_trait]
impl Handler for SftpHandler {
    type Error = russh::Error;

    async fn check_server_key(&mut self, _server_public_key: &PublicKey) -> Result<bool, Self::Error> {
        Ok(true)
    }
}

/// Config do cliente SSH com keepalive opcional (doc, secao 6, aviso
/// "Timeout agressivo": SSH keepalive + reconexao para Wi-Fi instavel do
/// Quest). Chamadas curtas de UI (listar diretorio) nao precisam disso;
/// `SftpFileSource`, que pode viver por horas durante playback, precisa.
fn client_config(keepalive: bool) -> client::Config {
    let mut cfg = client::Config::default();
    if keepalive {
        cfg.keepalive_interval = Some(Duration::from_secs(15));
        cfg.keepalive_max = 3;
    }
    cfg
}

/// Conecta e autentica — senha OU chave privada (T6.2 "avancado"), conforme
/// `SftpTarget::private_key` esta preenchido ou nao (ver `uri.rs`).
async fn connect_and_auth(t: &SftpTarget, keepalive: bool) -> Result<client::Handle<SftpHandler>, String> {
    t.validate()?;
    let mut session = client::connect(Arc::new(client_config(keepalive)), (t.host.as_str(), t.port), SftpHandler)
        .await
        .map_err(|e| e.to_string())?;

    let authenticated = if let Some(pem) = t.private_key.as_deref().filter(|k| !k.is_empty()) {
        let key_pair = russh::keys::decode_secret_key(pem, None).map_err(|e| format!("chave privada invalida: {e}"))?;
        session
            .authenticate_publickey(t.username.clone(), Arc::new(key_pair))
            .await
            .map_err(|e| e.to_string())?
    } else {
        session
            .authenticate_password(t.username.clone(), t.password.clone())
            .await
            .map_err(|e| e.to_string())?
    };

    if !authenticated {
        return Err("autenticacao SFTP falhou (usuario/senha ou chave privada invalidos)".to_string());
    }
    Ok(session)
}

/// Abre o canal SSH e sobe o subsistema `sftp` de alto nivel — usado so por
/// `list_directory` (que quer a conveniencia de `SftpSession::read_dir` com
/// metadata embutida). `SftpFileSource` usa `open_raw_sftp` abaixo em vez
/// desta, porque precisa do `read` posicional do `RawSftpSession`.
async fn open_sftp_session(session: &client::Handle<SftpHandler>) -> Result<SftpSession, String> {
    let channel = session.channel_open_session().await.map_err(|e| e.to_string())?;
    channel.request_subsystem(true, "sftp").await.map_err(|e| e.to_string())?;
    SftpSession::new(channel.into_stream()).await.map_err(|e| e.to_string())
}

/// Equivalente a `open_sftp_session` acima, mas devolve o `RawSftpSession`
/// de baixo nivel (T6.3 — precisa do `read(handle, offset, len)` posicional,
/// que o `File` de alto nivel nao expoe diretamente).
async fn open_raw_sftp(session: &client::Handle<SftpHandler>) -> Result<RawSftpSession, String> {
    let channel = session.channel_open_session().await.map_err(|e| e.to_string())?;
    channel.request_subsystem(true, "sftp").await.map_err(|e| e.to_string())?;
    let raw = RawSftpSession::new(channel.into_stream());
    raw.init().await.map_err(|e| e.to_string())?;
    Ok(raw)
}

async fn disconnect_best_effort(session: &client::Handle<SftpHandler>) {
    let _ = session.disconnect(Disconnect::ByApplication, "", "en").await;
}

pub struct SftpDirEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
}

/// Lista um diretorio remoto (T6.2 "opendir, readdir, stat") — tambem serve
/// como "testar conexao"/"status de conexao" para a UI (T6.4), igual ao
/// `smb::list_shares`/`smb::list_directory`: se autenticar e listar
/// funcionam, a conexao esta OK. Conexao curta, sem keepalive (nao vale a
/// pena para uma chamada de UI de poucos segundos). `path` vazio = diretorio
/// home do usuario SSH (`.`).
///
/// Usa `SftpSession::read_dir` de alto nivel: ja faz `OPENDIR`+`READDIR` em
/// laco e devolve os atributos (tamanho, tipo) que ja vem embutidos em cada
/// entrada da resposta `SSH_FXP_NAME` — ao contrario do SMB
/// (`smb::list_directory`), SFTP nao exige um `STAT`/`FSTAT` extra por
/// arquivo so pra descobrir tamanho/tipo.
pub fn list_directory(t: &SftpTarget, path: &str) -> Result<Vec<SftpDirEntry>, String> {
    let rt = new_runtime().map_err(|e| e.to_string())?;
    rt.block_on(async {
        let session = connect_and_auth(t, false).await?;
        let sftp = open_sftp_session(&session).await?;

        let dir_path = if path.is_empty() { "." } else { path };
        let entries = sftp.read_dir(dir_path).await.map_err(|e| e.to_string())?;

        let result = entries
            .map(|entry| {
                let metadata = entry.metadata();
                SftpDirEntry { name: entry.file_name(), is_dir: metadata.file_type().is_dir(), size: metadata.len() }
            })
            .collect();

        let _ = sftp.close().await;
        disconnect_best_effort(&session).await;
        Ok(result)
    })
}

/// Fonte de leitura posicional para o Demuxer via custom I/O (T6.3 —
/// integracao SFTP<->Demuxer). Ver doc do modulo sobre por que isto usa
/// `RawSftpSession::read` (pread nativo, sem cursor) em vez de um `File`
/// com `seek`+`read`, e sobre a mitigacao de reconexao em caso de idle
/// timeout do servidor.
///
/// Mantem a conexao SSH + sessao SFTP crua + handle de arquivo abertos pelo
/// tempo de vida do `PrefetchReader` que envolve isto. Keepalive fica ligado
/// (doc, secao 6): playback pode durar horas e o Wi-Fi do Quest cai.
pub struct SftpFileSource {
    runtime: Runtime,
    /// Guardado para poder reabrir do zero em `reconnect()` sem o chamador
    /// precisar reconstruir um `SftpFileSource` inteiro apos um erro
    /// transiente de rede.
    target: SftpTarget,
    session: Option<client::Handle<SftpHandler>>,
    raw: Option<RawSftpSession>,
    handle: Option<String>,
    size: u64,
}

impl SftpFileSource {
    pub fn open(t: &SftpTarget) -> Result<Self, String> {
        let runtime = new_runtime().map_err(|e| e.to_string())?;
        let target = t.clone();
        let (session, raw, handle, size) = runtime.block_on(Self::connect_open(&target))?;
        Ok(Self { runtime, target, session: Some(session), raw: Some(raw), handle: Some(handle), size })
    }

    async fn connect_open(t: &SftpTarget) -> Result<(client::Handle<SftpHandler>, RawSftpSession, String, u64), String> {
        let session = connect_and_auth(t, true).await?;
        let raw = open_raw_sftp(&session).await?;
        let handle = raw.open(t.path.as_str(), OpenFlags::READ, FileAttributes::empty()).await.map_err(|e| e.to_string())?.handle;
        let size = raw.fstat(handle.as_str()).await.map_err(|e| e.to_string())?.attrs.len();
        Ok((session, raw, handle, size))
    }

    /// Reabre a sessao SSH + o handle do arquivo do zero, no mesmo caminho.
    /// O offset em si nao precisa ser "restaurado" em lugar nenhum porque
    /// `read_range` sempre manda o offset explicito de novo na proxima
    /// tentativa — nao ha cursor de handle pra realinhar (essa e a vantagem
    /// do pread nativo, ver doc do modulo).
    fn reconnect(&mut self) -> Result<(), String> {
        // Solta o que sobrou da conexao anterior antes de abrir outra —
        // best-effort, o socket provavelmente ja caiu de qualquer forma.
        self.raw = None;
        self.session = None;

        let target = self.target.clone();
        let (session, raw, handle, size) = self.runtime.block_on(Self::connect_open(&target))?;
        self.session = Some(session);
        self.raw = Some(raw);
        self.handle = Some(handle);
        self.size = size;
        Ok(())
    }

    fn try_read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        let (Some(raw), Some(handle)) = (self.raw.as_ref(), self.handle.as_deref()) else {
            return Err(io::Error::other("SftpFileSource sem conexao ativa"));
        };

        let want = if offset >= self.size { 0 } else { (buf.len() as u64).min(self.size - offset) as u32 };
        if want == 0 {
            return Ok(0);
        }

        let chunks = split_range(offset, want, SFTP_MAX_READ_CHUNK);
        let results = self.runtime.block_on(join_all(chunks.iter().map(|&(chunk_offset, chunk_len)| raw.read(handle, chunk_offset, chunk_len))));

        let mut total = 0usize;
        for (idx, result) in results.into_iter().enumerate() {
            let (chunk_offset, chunk_len) = chunks[idx];
            match result {
                Ok(data) => {
                    let n = data.data.len();
                    let start_in_buf = (chunk_offset - offset) as usize;
                    buf[start_in_buf..start_in_buf + n].copy_from_slice(&data.data[..n]);
                    total += n;
                    if n < chunk_len as usize {
                        // Servidor devolveu menos do que pedido sem sinalizar
                        // EOF explicito (permitido pelo protocolo), ou este
                        // era o chunk final legitimo — os chunks seguintes
                        // (se buscados) nao formam um prefixo contiguo
                        // confiavel, entao para a remontagem aqui.
                        break;
                    }
                }
                Err(SftpError::Status(status)) if status.status_code == StatusCode::Eof => break,
                Err(e) => return Err(io::Error::other(e.to_string())),
            }
        }
        Ok(total)
    }
}

impl RangeSource for SftpFileSource {
    fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        match self.try_read_range(offset, buf) {
            Ok(n) => Ok(n),
            Err(_first_err) => {
                // Conexao provavelmente caiu (timeout/Wi-Fi instavel do
                // Quest, doc secao 6) — reconecta do zero uma vez e tenta a
                // MESMA leitura de novo antes de propagar o erro pro
                // Demuxer.
                self.reconnect().map_err(io::Error::other)?;
                self.try_read_range(offset, buf)
            }
        }
    }

    fn len(&self) -> Option<u64> {
        Some(self.size)
    }
}

impl Drop for SftpFileSource {
    fn drop(&mut self) {
        // Fecha handle/sessao/conexao de forma limpa, best-effort — se ja
        // estao mortos (conexao caiu) isso so falha silenciosamente, o que
        // e aceitavel dentro de um Drop.
        let handle = self.handle.take();
        let raw = self.raw.take();
        let session = self.session.take();
        self.runtime.block_on(async {
            if let (Some(raw), Some(handle)) = (raw.as_ref(), handle) {
                let _ = raw.close(handle).await;
            }
            if let Some(session) = session {
                disconnect_best_effort(&session).await;
            }
        });
    }
}
