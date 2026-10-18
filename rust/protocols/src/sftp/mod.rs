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
use crate::session_pool::{MAX_IDLE_PER_KEY, PoolKey, SESSION_TTL, SessionPool};
use futures_util::future::join_all;
use russh::Disconnect;
use russh::client::{self, Handler};
use russh::keys::{PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh_sftp::client::error::Error as SftpError;
use russh_sftp::client::{RawSftpSession, SftpSession};
use russh_sftp::protocol::{Data, FileAttributes, OpenFlags, StatusCode};
use std::io;
use std::sync::{Arc, LazyLock};
use std::time::Duration;
use tokio::runtime::Runtime;

fn new_runtime() -> io::Result<Runtime> {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
}

/// Ate quantos bytes pedir num unico `SSH_FXP_READ` — um pouco abaixo do
/// `max_packet_len` default de `russh_sftp` (256KiB) pra sobrar folga de
/// overhead de protocolo. Ver doc do modulo sobre o laco de leitura.
const SFTP_MAX_READ_CHUNK: u32 = 256 * 1024 - 4096;

/// Granularidade interna de `read_chunk_filling` — ver comentario la sobre
/// por que um chunk de `SFTP_MAX_READ_CHUNK` precisa ser sub-dividido e
/// pedido em paralelo em vez de preenchido com leituras sequenciais.
const SFTP_SUB_READ_SIZE: u32 = 64 * 1024;
const SFTP_SUB_READ_ROUNDS: usize = 4;

/// Quantos chunks de ~252KB ficam em voo ao mesmo tempo, numa janela
/// deslizante: cada chunk que termina libera o proximo, sem esperar os
/// demais. Antes eram lotes de 4 com barreira (o lote seguinte so saia quando
/// o mais lento do anterior voltava); medido no Quest 3, a janela de 16 subiu
/// a vazao de ~49 para ~70 MB/s.
///
/// Historico do limite antigo: disparar os 48 chunks de um bloco de 12MB de
/// uma vez coincidiu com uma regressao no Quest 3 (decFps ~30 -> 10). A causa
/// foi refutada depois (o gargalo era vazao sustentada), mas os dois riscos
/// de um burst grande continuam: cada `SSH_FXP_READ` custa CPU de cifra que
/// disputa nucleo com o decode, e um burst que estoura a janela do canal SSH
/// ou um limite do servidor faz uma falha disparar reconexao completa. Por
/// isso a janela tem teto e cada chunk tem retry proprio (ver
/// `read_chunks_windowed`).
const SFTP_WINDOW_CHUNKS: usize = 16;

/// Quantas vezes tentar de novo SO o(s) chunk(s) que falharam dentro da
/// janela, antes de desistir e deixar o erro propagar para `read_range`
/// (que ai sim reconecta a sessao inteira). Sem isto, uma unica falha
/// transiente de UM chunk entre os concorrentes descartava os que
/// tinham vindo certos e forcava reconexao completa (handshake SSH + auth +
/// reabrir arquivo) so pra refazer os mesmos ~12MB do zero.
const SFTP_CHUNK_RETRY_ATTEMPTS: usize = 2;

/// Timeout maximo para handshake TCP e negociacao/autenticacao SSH.
const SFTP_CONNECT_TIMEOUT: Duration = Duration::from_secs(6);

/// Tempo maximo sem NENHUM chunk da janela concluir antes de tratar como stall
/// de rede (o chamador reconecta). Mede progresso, nao a duracao de cada
/// chunk: assim a vazao minima tolerada nao depende do tamanho da janela, e
/// uma conexao morta e detectada em 3,5s, como quando o teto era do lote de
/// ~1MB. Em regime normal no Quest 3, 1MB leva ~60-100ms (p95 de 12MB =
/// 1,2s); 3,5s e ~35x a mediana, o bastante para absorver jitter de Wi-Fi e
/// ainda recuperar stalls pontuais antes que o buffer do demuxer esvazie.
const SFTP_STALL_TIMEOUT: Duration = Duration::from_millis(3500);

/// Teto de tempo para retentativa de chunks individuais falhos dentro da janela.
const SFTP_CHUNK_RETRY_TIMEOUT: Duration = Duration::from_millis(2000);

/// Handler minimo exigido pelo `russh::client` para receber eventos da
/// sessao SSH (banner, checagem de host key, etc). `check_server_key`
/// aceita QUALQUER chave — ver `[!WARNING]` no doc do modulo. Todos os
/// outros callbacks usam a implementacao default do trait.
struct SftpHandler;

impl Handler for SftpHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        _server_public_key: &PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
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

/// `client::connect` da russh cria o socket sem desligar o Nagle; com
/// requisicoes SFTP pequenas isso custa ~44 ms por chamada (delayed ACK do
/// servidor), entao o socket e criado aqui com `TCP_NODELAY`.
async fn connect_nodelay(
    t: &SftpTarget,
    keepalive: bool,
) -> Result<client::Handle<SftpHandler>, String> {
    let socket = tokio::net::TcpStream::connect((t.host.as_str(), t.port))
        .await
        .map_err(|e| e.to_string())?;
    socket.set_nodelay(true).map_err(|e| e.to_string())?;
    client::connect_stream(Arc::new(client_config(keepalive)), socket, SftpHandler)
        .await
        .map_err(|e| e.to_string())
}

/// Conecta e autentica — senha OU chave privada (T6.2 "avancado"), conforme
/// `SftpTarget::private_key` esta preenchido ou nao (ver `uri.rs`).
async fn connect_and_auth(
    t: &SftpTarget,
    keepalive: bool,
) -> Result<client::Handle<SftpHandler>, String> {
    t.validate()?;
    tokio::time::timeout(SFTP_CONNECT_TIMEOUT, async {
        let mut session = match connect_nodelay(t, keepalive).await {
            Ok(session) => {
                log::info!(
                    "SFTP: conexao TCP/SSH estabelecida com {}:{}",
                    t.host,
                    t.port
                );
                session
            }
            Err(e) => {
                log::warn!("SFTP: falha ao conectar em {}:{}: {e}", t.host, t.port);
                return Err(e);
            }
        };

        let authenticated = if let Some(pem) = t.private_key.as_deref().filter(|k| !k.is_empty()) {
            let key_pair = russh::keys::decode_secret_key(pem, None)
                .map_err(|e| format!("chave privada invalida: {e}"))?;
            let key_with_hash = PrivateKeyWithHashAlg::new(
                Arc::new(key_pair),
                session
                    .best_supported_rsa_hash()
                    .await
                    .map_err(|e| e.to_string())?
                    .flatten(),
            );
            session
                .authenticate_publickey(t.username.clone(), key_with_hash)
                .await
                .map_err(|e| e.to_string())?
        } else {
            session
                .authenticate_password(t.username.clone(), t.password.clone())
                .await
                .map_err(|e| e.to_string())?
        };

        if !authenticated.success() {
            log::warn!("SFTP: autenticacao rejeitada para usuario {}", t.username);
            return Err(
                "autenticacao SFTP falhou (usuario/senha ou chave privada invalidos)".to_string(),
            );
        }
        log::info!("SFTP: autenticado como {}", t.username);
        Ok(session)
    })
    .await
    .map_err(|_| {
        format!(
            "SFTP: timeout de conexao/autenticacao apos {}s",
            SFTP_CONNECT_TIMEOUT.as_secs()
        )
    })?
}

/// Abre o canal SSH e sobe o subsistema `sftp` de alto nivel — usado so por
/// `list_directory` (que quer a conveniencia de `SftpSession::read_dir` com
/// metadata embutida). `SftpFileSource` usa `open_raw_sftp` abaixo em vez
/// desta, porque precisa do `read` posicional do `RawSftpSession`.
async fn open_sftp_session(session: &client::Handle<SftpHandler>) -> Result<SftpSession, String> {
    let channel = session
        .channel_open_session()
        .await
        .map_err(|e| e.to_string())?;
    channel
        .request_subsystem(true, "sftp")
        .await
        .map_err(|e| e.to_string())?;
    SftpSession::new(channel.into_stream())
        .await
        .map_err(|e| e.to_string())
}

/// Equivalente a `open_sftp_session` acima, mas devolve o `RawSftpSession`
/// de baixo nivel (T6.3 — precisa do `read(handle, offset, len)` posicional,
/// que o `File` de alto nivel nao expoe diretamente).
async fn open_raw_sftp(session: &client::Handle<SftpHandler>) -> Result<RawSftpSession, String> {
    let channel = session
        .channel_open_session()
        .await
        .map_err(|e| e.to_string())?;
    channel
        .request_subsystem(true, "sftp")
        .await
        .map_err(|e| e.to_string())?;
    let raw = RawSftpSession::new(channel.into_stream());
    raw.init().await.map_err(|e| e.to_string())?;
    Ok(raw)
}

async fn disconnect_best_effort(session: &client::Handle<SftpHandler>) {
    let _ = session
        .disconnect(Disconnect::ByApplication, "", "en")
        .await;
}

pub struct SftpDirEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub mtime: i64,
}

struct SftpPooledSession {
    session: client::Handle<SftpHandler>,
    sftp: SftpSession,
}

impl SftpPooledSession {
    async fn connect(t: &SftpTarget) -> Result<Self, String> {
        let session = connect_and_auth(t, false).await?;
        let sftp = match open_sftp_session(&session).await {
            Ok(sftp) => sftp,
            Err(e) => {
                disconnect_best_effort(&session).await;
                return Err(e);
            }
        };
        Ok(Self { session, sftp })
    }

    async fn close(self) {
        let _ = self.sftp.close().await;
        disconnect_best_effort(&self.session).await;
    }
}

static SFTP_POOL: LazyLock<SessionPool<SftpPooledSession>> =
    LazyLock::new(|| SessionPool::new(SESSION_TTL, MAX_IDLE_PER_KEY));

fn pool_key(t: &SftpTarget) -> PoolKey {
    PoolKey::new(
        &t.host,
        t.port,
        &[
            &t.username,
            &t.password,
            t.private_key.as_deref().unwrap_or(""),
        ],
    )
}

/// Closes sessions in the background so a dead server never stalls the caller.
fn dispose(sessions: Vec<SftpPooledSession>) {
    for session in sessions {
        crate::POOL_RUNTIME.spawn(session.close());
    }
}

fn checkout(t: &SftpTarget) -> Option<SftpPooledSession> {
    let (session, expired) = SFTP_POOL.checkout(&pool_key(t));
    dispose(expired);
    session
}

fn checkin(t: &SftpTarget, session: SftpPooledSession) {
    dispose(SFTP_POOL.checkin(pool_key(t), session));
}

/// Drops every pooled session of a server (called when the user deletes it).
pub fn close_pooled_sessions(host: &str, port: u16) {
    dispose(SFTP_POOL.drain_server(host, port));
}

/// Lists `dir_path` on a pooled session; a pooled session may be dead (server
/// restart, Wi-Fi blip), so a failure on it is retried once on a fresh one.
/// On success the session is returned still checked out.
async fn read_dir_on_session(
    t: &SftpTarget,
    dir_path: &str,
) -> Result<(SftpPooledSession, russh_sftp::client::fs::ReadDir), String> {
    if let Some(session) = checkout(t) {
        match session.sftp.read_dir(dir_path).await {
            Ok(entries) => return Ok((session, entries)),
            Err(e) => {
                log::warn!("SFTP: read_dir failed on a pooled session ({e}), reconnecting...");
                dispose(vec![session]);
            }
        }
    }
    let session = SftpPooledSession::connect(t).await?;
    match session.sftp.read_dir(dir_path).await {
        Ok(entries) => Ok((session, entries)),
        Err(e) => {
            dispose(vec![session]);
            Err(e.to_string())
        }
    }
}

/// Lists a remote directory, reusing an idle pooled SFTP session to avoid new handshakes.
pub fn list_directory(t: &SftpTarget, path: &str) -> Result<Vec<SftpDirEntry>, String> {
    crate::POOL_RUNTIME.block_on(async {
        let dir_path = if path.is_empty() { "." } else { path };
        let (session, entries) = read_dir_on_session(t, dir_path).await?;
        checkin(t, session);

        let result = entries
            .map(|entry| {
                let metadata = entry.metadata();
                SftpDirEntry {
                    name: entry.file_name(),
                    is_dir: metadata.file_type().is_dir(),
                    size: metadata.len(),
                    mtime: metadata.mtime.map(|m| m as i64).unwrap_or(0),
                }
            })
            .collect();

        Ok(result)
    })
}

/// Recursive folder walk checking for media files, using a dedicated session from the pool.
pub fn scan_has_media(
    t: &SftpTarget,
    path: &str,
) -> Result<crate::folder_scan::ScanResult, String> {
    crate::POOL_RUNTIME.block_on(async {
        let deadline = crate::folder_scan::deadline_from_now();

        let start = if path.is_empty() {
            ".".to_string()
        } else {
            path.to_string()
        };
        let (session, first_entries) = read_dir_on_session(t, &start).await?;

        let mut pending_entries = Some(first_entries);
        let mut stack = vec![start];
        let (mut found, mut timed_out, mut skipped_unreadable) = (false, false, false);

        while let Some(current) = stack.pop() {
            if std::time::Instant::now() > deadline {
                timed_out = true;
                break;
            }
            let entries = match pending_entries.take() {
                Some(entries) => entries,
                None => match session.sftp.read_dir(&current).await {
                    Ok(e) => e,
                    Err(_) => {
                        skipped_unreadable = true;
                        continue;
                    }
                },
            };
            for entry in entries {
                let metadata = entry.metadata();
                let name = entry.file_name();
                if metadata.file_type().is_dir() {
                    let child = if current == "." {
                        name
                    } else {
                        format!("{}/{}", current, name)
                    };
                    stack.push(child);
                } else if crate::folder_scan::is_media_filename(&name) {
                    found = true;
                    break;
                }
            }
            if found {
                break;
            }
        }

        // A failed listing may mean the session died: do not give it back to the pool.
        if skipped_unreadable {
            dispose(vec![session]);
        } else {
            checkin(t, session);
        }
        Ok(crate::folder_scan::ScanResult::from_walk(
            found,
            timed_out,
            skipped_unreadable,
        ))
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
        Ok(Self {
            runtime,
            target,
            session: Some(session),
            raw: Some(raw),
            handle: Some(handle),
            size,
        })
    }

    async fn connect_open(
        t: &SftpTarget,
    ) -> Result<(client::Handle<SftpHandler>, RawSftpSession, String, u64), String> {
        let session = connect_and_auth(t, true).await?;
        let raw = tokio::time::timeout(SFTP_CONNECT_TIMEOUT, open_raw_sftp(&session))
            .await
            .map_err(|_| {
                format!(
                    "SFTP: timeout ao abrir subsistema SFTP apos {}s",
                    SFTP_CONNECT_TIMEOUT.as_secs()
                )
            })??;
        let handle = tokio::time::timeout(
            SFTP_CONNECT_TIMEOUT,
            raw.open(t.path.as_str(), OpenFlags::READ, FileAttributes::empty()),
        )
        .await
        .map_err(|_| {
            format!(
                "SFTP: timeout ao abrir arquivo {} apos {}s",
                t.path,
                SFTP_CONNECT_TIMEOUT.as_secs()
            )
        })?
        .map_err(|e| {
            log::warn!("SFTP: falha ao abrir arquivo {}: {e}", t.path);
            e.to_string()
        })?
        .handle;
        let size = tokio::time::timeout(SFTP_CONNECT_TIMEOUT, raw.fstat(handle.as_str()))
            .await
            .map_err(|_| {
                format!(
                    "SFTP: timeout ao obter tamanho de {} apos {}s",
                    t.path,
                    SFTP_CONNECT_TIMEOUT.as_secs()
                )
            })?
            .map_err(|e| {
                log::warn!("SFTP: falha ao obter tamanho de {}: {e}", t.path);
                e.to_string()
            })?
            .attrs
            .len();
        log::info!("SFTP: arquivo aberto {} ({size} bytes)", t.path);
        Ok((session, raw, handle, size))
    }

    /// Reabre a sessao SSH + o handle do arquivo do zero, no mesmo caminho.
    /// O offset em si nao precisa ser "restaurado" em lugar nenhum porque
    /// `read_range` sempre manda o offset explicito de novo na proxima
    /// tentativa — nao ha cursor de handle pra realinhar (essa e a vantagem
    /// do pread nativo, ver doc do modulo).
    fn reconnect(&mut self) -> Result<(), String> {
        // Reconexao completa (handshake SSH + auth + reabrir arquivo) e
        // MUITO mais cara que uma leitura normal — se isto aparecer com
        // frequencia no log durante playback, e sinal de que algo esta
        // derrubando a conexao com regularidade (ver SFTP_WINDOW_CHUNKS).
        log::warn!("SftpFileSource: reconectando apos falha de leitura");
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

    /// Le um chunk ate preenche-lo por completo, tratando "servidor devolveu
    /// menos do que pedido sem sinalizar EOF" como leitura PARCIAL LEGITIMA
    /// (permitida pelo protocolo) em vez de fim de dados. Achado original
    /// (docs/NETWORK-IO-PERFORMANCE.md): este NAS devolve ~30KB por
    /// `SSH_FXP_READ` independente do quanto se pede, bem abaixo de
    /// `SFTP_MAX_READ_CHUNK` (~252KB) — sem preencher em varias leituras, o
    /// "bloco" efetivo do `PrefetchReader` colapsava pro tamanho real de
    /// resposta do servidor.
    ///
    /// Refinamento desta sessao: preencher um chunk de ~252KB a ~30KB por
    /// vez SEQUENCIALMENTE (uma leitura por vez, esperando cada resposta)
    /// significa ~8 idas-e-voltas em serie por chunk — a concorrencia de
    /// `SFTP_WINDOW_CHUNKS` so se aplica ENTRE chunks, nao dentro de
    /// um. Aqui o chunk e sub-dividido em pedacos de `SFTP_SUB_READ_SIZE` e
    /// todos pedidos em paralelo por rodada (`join_all`) — cada rodada
    /// preenche o que ainda falta; o que sobrar apos `SFTP_SUB_READ_ROUNDS`
    /// cai pro laco sequencial original (garante terminar sempre, sem
    /// arriscar buracos silenciosos de zeros no meio do bloco).
    async fn read_chunk_filling(
        raw: &RawSftpSession,
        handle: &str,
        chunk_offset: u64,
        chunk_len: u32,
    ) -> Result<Data, SftpError> {
        let mut acc = vec![0u8; chunk_len as usize];
        let mut hit_end_at: Option<u32> = None;
        let mut missing: Vec<(u64, u32)> = split_range(chunk_offset, chunk_len, SFTP_SUB_READ_SIZE);

        for _ in 0..SFTP_SUB_READ_ROUNDS {
            if missing.is_empty() || hit_end_at.is_some() {
                break;
            }
            let results =
                join_all(missing.iter().map(|&(off, len)| raw.read(handle, off, len))).await;
            let mut next_missing = Vec::new();
            for (&(off, len), result) in missing.iter().zip(results) {
                let rel = (off - chunk_offset) as u32;
                match result {
                    Ok(data) => {
                        let n = (data.data.len() as u32).min(len);
                        if n == 0 {
                            hit_end_at = Some(hit_end_at.map_or(rel, |v| v.min(rel)));
                            continue;
                        }
                        acc[rel as usize..(rel + n) as usize]
                            .copy_from_slice(&data.data[..n as usize]);
                        if n < len {
                            next_missing.push((off + n as u64, len - n));
                        }
                    }
                    Err(SftpError::Status(status)) if status.status_code == StatusCode::Eof => {
                        hit_end_at = Some(hit_end_at.map_or(rel, |v| v.min(rel)));
                    }
                    Err(e) => return Err(e),
                }
            }
            missing = next_missing;
        }

        if hit_end_at.is_none() {
            for (mut sub_offset, mut sub_remaining) in missing {
                while sub_remaining > 0 {
                    match raw.read(handle, sub_offset, sub_remaining).await {
                        Ok(data) => {
                            let n = data.data.len() as u32;
                            if n == 0 {
                                let rel = (sub_offset - chunk_offset) as u32;
                                hit_end_at = Some(hit_end_at.map_or(rel, |v| v.min(rel)));
                                break;
                            }
                            let rel = (sub_offset - chunk_offset) as usize;
                            acc[rel..rel + n as usize].copy_from_slice(&data.data[..n as usize]);
                            sub_offset += n as u64;
                            sub_remaining -= n;
                        }
                        Err(SftpError::Status(status)) if status.status_code == StatusCode::Eof => {
                            let rel = (sub_offset - chunk_offset) as u32;
                            hit_end_at = Some(hit_end_at.map_or(rel, |v| v.min(rel)));
                            break;
                        }
                        Err(e) => return Err(e),
                    }
                }
            }
        }

        if let Some(end) = hit_end_at {
            acc.truncate(end as usize);
        }
        Ok(Data { id: 0, data: acc })
    }

    fn try_read_range_into(
        &mut self,
        offset: u64,
        buf: &mut [u8],
        total: &mut usize,
    ) -> io::Result<()> {
        let (Some(raw), Some(handle)) = (self.raw.as_ref(), self.handle.as_deref()) else {
            log::warn!(
                "SFTP: try_read_range chamado sem conexao ativa (offset {offset}, buf {} bytes)",
                buf.len()
            );
            return Err(io::Error::other("SftpFileSource sem conexao ativa"));
        };
        log::info!(
            "SFTP: try_read_range offset={offset} pedindo {} bytes",
            buf.len()
        );

        let want = if offset >= self.size {
            0
        } else {
            (buf.len() as u64).min(self.size - offset) as u32
        };
        if want == 0 {
            return Ok(());
        }

        let chunks = split_range(offset, want, SFTP_MAX_READ_CHUNK);
        let results = self.runtime.block_on(read_chunks_windowed(
            &chunks,
            SFTP_WINDOW_CHUNKS,
            SFTP_STALL_TIMEOUT,
            SFTP_CHUNK_RETRY_TIMEOUT,
            SFTP_CHUNK_RETRY_ATTEMPTS,
            |chunk_offset, chunk_len| {
                Self::read_chunk_filling(raw, handle, chunk_offset, chunk_len)
            },
        ));

        for (idx, result) in results.into_iter().enumerate() {
            let (chunk_offset, chunk_len) = chunks[idx];
            match result {
                Ok(data) => {
                    let n = data.data.len();
                    let start_in_buf = (chunk_offset - offset) as usize;
                    buf[start_in_buf..start_in_buf + n].copy_from_slice(&data.data[..n]);
                    *total += n;
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
                Err(SftpError::Timeout) => {
                    log::warn!(
                        "SFTP: sem resposta do servidor no offset {chunk_offset} por {:?} (stall de rede detectado)",
                        SFTP_STALL_TIMEOUT
                    );
                    return Err(io::Error::new(
                        io::ErrorKind::TimedOut,
                        format!(
                            "SFTP: sem resposta do servidor por {:?} (stall de rede)",
                            SFTP_STALL_TIMEOUT
                        ),
                    ));
                }
                Err(e) => {
                    log::warn!(
                        "SFTP: leitura em offset {chunk_offset} (tam {chunk_len}) falhou: {e}"
                    );
                    return Err(io::Error::other(e.to_string()));
                }
            }
        }
        Ok(())
    }

    #[allow(dead_code)]
    fn try_read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        let mut total = 0usize;
        self.try_read_range_into(offset, buf, &mut total)?;
        Ok(total)
    }
}

/// Le `chunks` com no maximo `window` em voo, numa janela deslizante (cada
/// chunk que termina libera o proximo, sem barreira por lote), e devolve os
/// resultados NA ORDEM de `chunks` — quem monta o buffer depende disso para
/// parar no primeiro chunk curto ou EOF.
///
/// - Um chunk que falha com erro de verdade e relido ate `retry_attempts`
///   vezes (cada uma com `retry_timeout`) sem descartar os que vieram certos
///   nem forcar reconexao. `Eof` e um resultado legitimo e nunca e repetido.
/// - Se nenhum chunk concluir em `stall_timeout`, a leitura para com
///   `Err(Timeout)` na posicao do chunk que estava sendo esperado: a conexao
///   provavelmente caiu e repetir na mesma so gastaria mais tempo.
/// - No primeiro `Err` (incluindo `Eof`) a janela e abandonada e os chunks
///   em voo sao cancelados: os seguintes nao seriam usados. O vetor devolvido
///   termina nesse `Err`.
async fn read_chunks_windowed<F, Fut>(
    chunks: &[(u64, u32)],
    window: usize,
    stall_timeout: Duration,
    retry_timeout: Duration,
    retry_attempts: usize,
    fetch: F,
) -> Vec<Result<Data, SftpError>>
where
    F: Fn(u64, u32) -> Fut,
    Fut: std::future::Future<Output = Result<Data, SftpError>>,
{
    use futures_util::stream::{self, StreamExt};

    let fetch = &fetch;
    let mut in_order = stream::iter(chunks.iter().copied())
        .map(|(offset, len)| async move {
            let mut result = fetch(offset, len).await;
            for _ in 0..retry_attempts {
                match &result {
                    Ok(_) => break,
                    Err(SftpError::Status(status)) if status.status_code == StatusCode::Eof => {
                        break;
                    }
                    Err(_) => {}
                }
                result = tokio::time::timeout(retry_timeout, fetch(offset, len))
                    .await
                    .unwrap_or(Err(SftpError::Timeout));
            }
            result
        })
        .buffered(window);

    let mut results = Vec::with_capacity(chunks.len());
    while results.len() < chunks.len() {
        let next = match tokio::time::timeout(stall_timeout, in_order.next()).await {
            Ok(Some(result)) => result,
            Ok(None) => break,
            Err(_stalled) => Err(SftpError::Timeout),
        };
        let failed = next.is_err();
        results.push(next);
        if failed {
            break;
        }
    }
    results
}

impl RangeSource for SftpFileSource {
    fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        let mut total_read = 0usize;
        match self.try_read_range_into(offset, buf, &mut total_read) {
            Ok(()) => Ok(total_read),
            Err(first_err) => {
                // Conexao provavelmente caiu ou atingiu timeout (stall de rede).
                // Reconecta do zero e retoma a partir do offset onde parou,
                // preservando os bytes ja gravados em `buf` em vez de descartar
                // progresso util (especialmente critico em blocos de 12MB para 8K).
                log::warn!(
                    "SftpFileSource: stall/falha na leitura (offset {offset}, lidos {total_read}/{} bytes): {first_err}. Reconectando...",
                    buf.len()
                );
                self.reconnect().map_err(io::Error::other)?;
                let remaining_offset = offset + total_read as u64;
                let remaining_buf = &mut buf[total_read..];
                let mut resumed_read = 0usize;
                let res =
                    self.try_read_range_into(remaining_offset, remaining_buf, &mut resumed_read);
                total_read += resumed_read;
                if total_read > 0 && res.is_err() {
                    log::warn!(
                        "SftpFileSource: entregando bloco parcial de {total_read} bytes apos falha na retomada"
                    );
                    Ok(total_read)
                } else {
                    res.map(|()| total_read)
                }
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicUsize, Ordering};

    const T: Duration = Duration::from_millis(200);

    fn chunk_list(n: u64) -> Vec<(u64, u32)> {
        (0..n).map(|i| (i * 100, 100)).collect()
    }

    fn data_for(offset: u64) -> Data {
        Data {
            id: 0,
            data: offset.to_be_bytes().to_vec(),
        }
    }

    fn eof() -> SftpError {
        SftpError::Status(russh_sftp::protocol::Status {
            id: 0,
            status_code: StatusCode::Eof,
            error_message: String::new(),
            language_tag: String::new(),
        })
    }

    fn payload(result: &Result<Data, SftpError>) -> u64 {
        u64::from_be_bytes(
            result.as_ref().expect("chunk deveria ter lido").data[..8]
                .try_into()
                .unwrap(),
        )
    }

    #[tokio::test]
    async fn results_keep_chunk_order_even_when_later_chunks_finish_first() {
        let chunks = chunk_list(8);
        // Quanto maior o offset, mais rapido responde: a ordem de chegada e a inversa da de pedido.
        let results = read_chunks_windowed(&chunks, 8, T, T, 0, |offset, _| async move {
            tokio::time::sleep(Duration::from_millis(80 - offset / 10)).await;
            Ok(data_for(offset))
        })
        .await;

        let got: Vec<u64> = results.iter().map(payload).collect();
        let want: Vec<u64> = chunks.iter().map(|&(o, _)| o).collect();
        assert_eq!(
            got, want,
            "quem monta o buffer para no primeiro chunk curto: a ordem tem que ser a do pedido"
        );
    }

    #[tokio::test]
    async fn never_exceeds_window_and_keeps_it_full() {
        let in_flight = AtomicUsize::new(0);
        let peak = AtomicUsize::new(0);
        let chunks = chunk_list(40);

        let results = read_chunks_windowed(&chunks, 5, T, T, 0, |offset, _| {
            let (in_flight, peak) = (&in_flight, &peak);
            async move {
                let now = in_flight.fetch_add(1, Ordering::SeqCst) + 1;
                peak.fetch_max(now, Ordering::SeqCst);
                tokio::time::sleep(Duration::from_millis(5)).await;
                in_flight.fetch_sub(1, Ordering::SeqCst);
                Ok(data_for(offset))
            }
        })
        .await;

        assert_eq!(results.len(), 40);
        assert_eq!(
            peak.load(Ordering::SeqCst),
            5,
            "a janela deve encher (senao nao ha ganho) e nunca passar do teto"
        );
    }

    #[tokio::test]
    async fn transient_failure_retries_only_the_failed_chunk() {
        let calls: Mutex<HashMap<u64, usize>> = Mutex::new(HashMap::new());
        let chunks = chunk_list(6);

        let results = read_chunks_windowed(&chunks, 6, T, T, 2, |offset, _| {
            let attempt = {
                let mut calls = calls.lock().unwrap();
                let n = calls.entry(offset).or_insert(0);
                *n += 1;
                *n
            };
            async move {
                if offset == 300 && attempt == 1 {
                    Err(SftpError::IO("conexao resetada".into()))
                } else {
                    Ok(data_for(offset))
                }
            }
        })
        .await;

        assert!(results.iter().all(|r| r.is_ok()));
        let calls = calls.lock().unwrap();
        assert_eq!(calls[&300], 2, "o chunk que falhou e lido de novo");
        assert!(
            calls
                .iter()
                .filter(|&(&o, _)| o != 300)
                .all(|(_, &n)| n == 1),
            "os que vieram certos nao sao relidos"
        );
    }

    #[tokio::test]
    async fn exhausted_retries_leave_the_error_in_the_failed_chunks_slot() {
        let calls = AtomicUsize::new(0);
        let chunks = chunk_list(4);

        let results = read_chunks_windowed(&chunks, 4, T, T, 2, |offset, _| {
            let calls = &calls;
            async move {
                if offset == 200 {
                    calls.fetch_add(1, Ordering::SeqCst);
                    Err(SftpError::IO("conexao resetada".into()))
                } else {
                    Ok(data_for(offset))
                }
            }
        })
        .await;

        assert_eq!(calls.load(Ordering::SeqCst), 3, "1 tentativa + 2 retries");
        assert_eq!(
            results.len(),
            3,
            "o vetor termina no chunk que esgotou as tentativas"
        );
        assert!(matches!(results[2], Err(SftpError::IO(_))));
        assert!(
            results[0].is_ok() && results[1].is_ok(),
            "o progresso antes da falha e preservado"
        );
    }

    #[tokio::test]
    async fn eof_is_a_result_not_a_failure_and_is_not_retried() {
        let calls = AtomicUsize::new(0);
        let chunks = chunk_list(3);

        let results = read_chunks_windowed(&chunks, 3, T, T, 2, |offset, _| {
            let calls = &calls;
            async move {
                if offset == 200 {
                    calls.fetch_add(1, Ordering::SeqCst);
                    Err(eof())
                } else {
                    Ok(data_for(offset))
                }
            }
        })
        .await;

        assert_eq!(calls.load(Ordering::SeqCst), 1);
        assert!(
            matches!(&results[2], Err(SftpError::Status(s)) if s.status_code == StatusCode::Eof)
        );
    }

    #[tokio::test]
    async fn a_silent_chunk_stalls_the_window_and_stops_at_its_slot() {
        let started = AtomicUsize::new(0);
        let chunks = chunk_list(12);

        let t0 = std::time::Instant::now();
        let results =
            read_chunks_windowed(&chunks, 4, Duration::from_millis(60), T, 2, |offset, _| {
                started.fetch_add(1, Ordering::SeqCst);
                async move {
                    if offset == 300 {
                        std::future::pending::<()>().await;
                    }
                    Ok(data_for(offset))
                }
            })
            .await;

        assert_eq!(
            results.len(),
            4,
            "o vetor termina no chunk que travou: 3 lidos + o Timeout"
        );
        assert!(
            results[..3].iter().all(|r| r.is_ok()),
            "o progresso antes do stall e preservado"
        );
        assert!(matches!(results[3], Err(SftpError::Timeout)));
        assert!(
            t0.elapsed() < Duration::from_millis(400),
            "stall detectado pelo vigia, sem esperar retries"
        );
        assert!(
            started.load(Ordering::SeqCst) < 12,
            "chunks alem do stall nao sao disparados"
        );
    }

    #[tokio::test]
    async fn a_slow_but_progressing_link_is_not_a_stall() {
        let chunks = chunk_list(24);

        // 24 chunks x 15 ms em janela de 2 leva bem mais que o vigia de 50 ms no total,
        // mas cada chunk conclui bem antes: vazao baixa nao pode virar timeout.
        let results = read_chunks_windowed(
            &chunks,
            2,
            Duration::from_millis(50),
            T,
            0,
            |offset, _| async move {
                tokio::time::sleep(Duration::from_millis(15)).await;
                Ok(data_for(offset))
            },
        )
        .await;

        assert_eq!(results.len(), 24);
        assert!(results.iter().all(|r| r.is_ok()));
    }

    #[tokio::test]
    async fn first_error_abandons_the_window() {
        let started = AtomicUsize::new(0);
        let chunks = chunk_list(30);

        let results = read_chunks_windowed(&chunks, 3, T, T, 0, |offset, _| {
            started.fetch_add(1, Ordering::SeqCst);
            async move {
                tokio::time::sleep(Duration::from_millis(5)).await;
                if offset == 200 {
                    Err(SftpError::IO("conexao resetada".into()))
                } else {
                    Ok(data_for(offset))
                }
            }
        })
        .await;

        assert_eq!(results.len(), 3);
        assert!(matches!(results[2], Err(SftpError::IO(_))));
        assert!(
            started.load(Ordering::SeqCst) <= 6,
            "depois do erro nao ha mais leituras novas"
        );
    }

    #[tokio::test]
    async fn a_retry_that_hangs_reports_timeout() {
        let calls = AtomicUsize::new(0);
        let chunks = chunk_list(2);

        let results = read_chunks_windowed(
            &chunks,
            2,
            Duration::from_millis(500),
            Duration::from_millis(20),
            2,
            |offset, _| {
                let attempt = if offset == 100 {
                    calls.fetch_add(1, Ordering::SeqCst) + 1
                } else {
                    0
                };
                async move {
                    match (offset, attempt) {
                        (100, 1) => Err(SftpError::IO("resetada".into())),
                        (100, _) => std::future::pending().await,
                        _ => Ok(data_for(offset)),
                    }
                }
            },
        )
        .await;

        assert_eq!(
            calls.load(Ordering::SeqCst),
            3,
            "1 tentativa + 2 retries, cada retry com o seu timeout"
        );
        assert!(results[0].is_ok());
        assert!(matches!(results[1], Err(SftpError::Timeout)));
    }
}
