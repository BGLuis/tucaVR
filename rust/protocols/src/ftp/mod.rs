//! Cliente FTP (T6.1) construido sobre a crate `suppaftp` (pura Rust, API
//! bloqueante nativa) — diferente de SMB e SFTP, aqui NAO ha ponte
//! async->sync via `tokio::block_on` (ver `crate::smb`): a API de
//! `suppaftp::FtpStream` ja e sincrona de fabrica, o que casa diretamente
//! com o que o FFmpeg custom I/O exige (callbacks sincronos rodando na
//! thread que os chama — ver `crate::prefetch`).
//!
//! **Modo passivo e obrigatorio** (doc, secao 6, aviso "FTP modo passivo"):
//! o Quest 3 fica atras de NAT/firewall do roteador Wi-Fi, e o modo ativo
//! FTP (servidor conecta de volta ao cliente) simplesmente nao chega la.
//! `ImplFtpStream` ja nasce em `Mode::Passive` por padrao, mas fixamos isso
//! explicitamente com `set_mode` em vez de confiar no default da crate — se
//! uma versao futura da `suppaftp` mudar o default, nao queremos regressar
//! silenciosamente para ativo.
//!
//! **Seek via REST+RETR e o ponto mais delicado deste modulo** (doc, secao
//! 6, aviso "FTP seek e problematico"): o protocolo FTP nao tem um
//! primitivo de leitura posicional — so da pra pedir "envie o arquivo a
//! partir do byte X" (`REST X` + `RETR`), o que abre uma NOVA conexao de
//! dados. Isso e caro se feito a cada `read_range`. Mas o `PrefetchReader`
//! (`crate::prefetch`) que envolve `FtpFileSource` chama `read_range` com
//! offsets crescentes e contiguos durante playback sequencial normal — so
//! "pula" (offset descontinuo) quando o demuxer faz um seek de verdade
//! (cues no fim de um MKV, por exemplo). Entao `FtpFileSource` mantem UM
//! stream de dados aberto e sua posicao atual: se o offset pedido bate com
//! a posicao atual, so continua lendo do stream ja aberto (barato); so
//! reconecta (REST+RETR) quando o offset realmente muda. Reconectar a cada
//! chamada, ignorando esse fast-path, anularia todo o ganho de
//! batching de 4MB que o `PrefetchReader` foi desenhado pra dar (ver
//! `crate::prefetch`, topo do arquivo).
//!
//! **O que NAO esta implementado** (fora do escopo desta sessao, honesto em
//! vez de fingido, mesmo padrao de `crate::smb`): FTPS/TLS (a crate esta
//! configurada sem a feature de TLS — FTP em si e sempre texto claro, doc
//! secao 6 aviso "FTP senha em texto claro"; recomendacao la e usar SFTP
//! quando possivel), keepalive em cima de idle timeout do servidor (doc,
//! secao 6, nota "Timeout agressivo" — a `suppaftp` nao tem um equivalente
//! ao `ClientConfig::auto_reconnect` da `smb2`). `FtpFileSource::read_range`
//! reconecta a conexao de controle e tenta a mesma leitura mais uma vez se um
//! erro de I/O acontecer (mesmo padrao de `SftpFileSource`, ver
//! `crate::sftp`), mas nao ha keepalive proativo antes de um erro ocorrer.

pub mod uri;

pub use uri::{is_ftp_uri, redact, FtpTarget};

use crate::prefetch::RangeSource;
use std::io::{self, Read};
use std::net::ToSocketAddrs;
use std::time::Duration;
use suppaftp::list::File as FtpListFile;
use suppaftp::types::FileType;
use suppaftp::{FtpStream, Mode};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);

/// Login anonimo (T6.1 "login... + anonymous"): convencao padrao do
/// protocolo (RFC 1635) e usar o usuario literal `anonymous` com uma senha
/// "email-like" qualquer — muitos servidores nem validam o valor da senha,
/// so exigem que o campo nao esteja vazio.
const ANONYMOUS_USER: &str = "anonymous";
const ANONYMOUS_PASSWORD: &str = "anonymous@vrplayer.local";

fn resolve(host: &str, port: u16) -> io::Result<std::net::SocketAddr> {
    (host, port)
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, format!("nao foi possivel resolver {host}:{port}")))
}

/// Conecta, autentica (usuario/senha OU anonimo se `username` vazio) e forca
/// modo passivo. Usado tanto por `list_directory` (conexao curta) quanto por
/// `FtpFileSource::open` (conexao de vida longa pelo tempo do playback).
fn connect_and_login(t: &FtpTarget) -> Result<FtpStream, String> {
    let addr = resolve(&t.host, t.port).map_err(|e| e.to_string())?;
    let mut stream = FtpStream::connect_timeout(addr, CONNECT_TIMEOUT).map_err(|e| e.to_string())?;
    // T6.1: modo passivo obrigatorio — ver comentario no topo do arquivo.
    stream.set_mode(Mode::Passive);
    if t.username.is_empty() {
        stream.login(ANONYMOUS_USER, ANONYMOUS_PASSWORD)
    } else {
        stream.login(t.username.as_str(), t.password.as_str())
    }
    .map_err(|e| e.to_string())?;
    // TYPE I (binario) — sem isso alguns servidores fazem traducao de fim de
    // linha em modo ASCII (default do FTP), o que corromperia qualquer
    // arquivo de video. Tambem deixa SIZE/REST consistentes com o que RETR
    // de fato transfere.
    stream.transfer_type(FileType::Binary).map_err(|e| e.to_string())?;
    Ok(stream)
}

pub struct FtpDirEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
}

fn from_list_file(f: FtpListFile) -> FtpDirEntry {
    FtpDirEntry { name: f.name().to_string(), is_dir: f.is_directory(), size: f.size() as u64 }
}

/// Lista um diretorio no servidor (T6.1 "listar diretorios"). `path` vazio =
/// diretorio corrente (home do usuario logo apos o login).
///
/// Prefere `MLSD` (formato maquina-legivel, RFC 3659 — parsing determinístico
/// via `type=`/`size=` em vez de heuristica sobre a saida de `ls -l`), mas
/// nem todo servidor o implementa (por exemplo o `vsftpd` usado no ambiente
/// de teste deste crate, `docker/network-tests`, responde `500 Unknown
/// command` pra MLSD) — nesse caso cai pra `LIST` e usa o parser
/// POSIX/DOS que a propria `suppaftp` ja tem (`suppaftp::list::File::from_str`).
pub fn list_directory(t: &FtpTarget, path: &str) -> Result<Vec<FtpDirEntry>, String> {
    let mut stream = connect_and_login(t)?;
    let dir = if path.is_empty() { None } else { Some(path) };

    let entries = match stream.mlsd(dir) {
        Ok(lines) => lines.into_iter().filter_map(|line| FtpListFile::from_mlsx_line(&line).ok()).map(from_list_file).collect(),
        Err(_) => {
            let lines = stream.list(dir).map_err(|e| e.to_string())?;
            lines
                .into_iter()
                .filter_map(|line| line.parse::<FtpListFile>().ok())
                .filter(|f| f.name() != "." && f.name() != "..")
                .map(from_list_file)
                .collect()
        }
    };
    let _ = stream.quit();
    Ok(entries)
}

/// Fonte de leitura posicional para o Demuxer via custom I/O (T6.3). Mantem
/// uma unica conexao de controle FTP aberta pelo tempo de vida do
/// `PrefetchReader` que envolve isto, mais (no maximo) um stream de dados
/// aberto por vez — ver comentario no topo do arquivo sobre o fast-path de
/// leitura sequencial vs. reconexao via REST+RETR em seeks de verdade.
pub struct FtpFileSource {
    control: FtpStream,
    /// Guardado para poder reabrir a conexao de controle do zero em
    /// `reconnect()` sem o chamador precisar reconstruir um `FtpFileSource`
    /// inteiro apos um erro transiente de rede — mesmo padrao de
    /// `SftpFileSource::target` (ver sftp/mod.rs).
    target: FtpTarget,
    path: String,
    size: u64,
    /// Stream de dados atualmente aberto (RETR em andamento), se houver.
    /// Boxed porque o tipo concreto que `suppaftp::FtpStream::retr_as_stream`
    /// devolve (`DataStream<NoTlsStream>`) nao e nomeavel fora da crate
    /// (`NoTlsStream` nao e reexportado publicamente) — so precisamos dele
    /// como `Read`, entao um trait object resolve sem downcast nenhum.
    data: Option<Box<dyn Read + Send>>,
    /// Offset absoluto (no arquivo) do proximo byte que sera lido de `data`.
    /// So valido enquanto `data` for `Some`.
    data_pos: u64,
    /// `true` se o `data` atual ja foi lido ate o fim (RETR natural, servidor
    /// ja mandou "226 Transfer complete") — decide se o fechamento deve
    /// chamar `finalize_retr_stream` (fim normal) ou `abort` (interrompido no
    /// meio, precisa de `ABOR` explicito antes do servidor aceitar outro
    /// comando na conexao de controle).
    data_exhausted: bool,
}

impl FtpFileSource {
    pub fn open(t: &FtpTarget) -> Result<Self, String> {
        let mut control = connect_and_login(t)?;
        let size = control.size(&t.path).map_err(|e| e.to_string())? as u64;
        Ok(Self { control, target: t.clone(), path: t.path.clone(), size, data: None, data_pos: 0, data_exhausted: false })
    }

    /// Reabre a conexao de controle do zero, no mesmo caminho — mesmo padrao
    /// de `SftpFileSource::reconnect` (sftp/mod.rs). A conexao antiga
    /// provavelmente ja caiu, entao so descarta o estado velho (sem tentar um
    /// `close_data_stream`/`quit` "limpo", que exigiria a conexao ainda viva).
    fn reconnect(&mut self) -> Result<(), String> {
        log::warn!("FtpFileSource: reconectando apos falha de leitura");
        self.data = None;
        self.data_exhausted = false;
        self.control = connect_and_login(&self.target)?;
        self.data_pos = 0;
        Ok(())
    }

    /// Fecha o stream de dados atual (se houver), com o encerramento correto
    /// pro estado em que ele esta: `finalize_retr_stream` se o RETR ja
    /// terminou sozinho, `abort` (envia `ABOR`) se foi interrompido no meio —
    /// ver doc de `data_exhausted` no struct.
    fn close_data_stream(&mut self) {
        if let Some(stream) = self.data.take() {
            let result = if self.data_exhausted {
                self.control.finalize_retr_stream(stream)
            } else {
                self.control.abort(stream)
            };
            if let Err(e) = result {
                log::warn!("FtpFileSource: erro ao fechar stream de dados anterior: {e}");
            }
        }
        self.data_exhausted = false;
    }

    /// Abre um novo stream de dados a partir de `offset` — `REST offset` (se
    /// `offset > 0`) seguido de `RETR`. Isto e a "reconexao" cara mencionada
    /// no doc (secao 6): so deve ser chamada quando o offset pedido realmente
    /// difere do que ja esta aberto.
    fn open_data_stream(&mut self, offset: u64) -> io::Result<()> {
        if offset > 0 {
            self.control.resume_transfer(offset as usize).map_err(|e| io::Error::other(e.to_string()))?;
        }
        let stream = self.control.retr_as_stream(&self.path).map_err(|e| io::Error::other(e.to_string()))?;
        self.data = Some(Box::new(stream));
        self.data_pos = offset;
        self.data_exhausted = false;
        Ok(())
    }

    fn try_read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() || offset >= self.size {
            return Ok(0);
        }

        let needs_reopen = match &self.data {
            None => true,
            Some(_) => self.data_pos != offset,
        };
        if needs_reopen {
            // Fast-path: leitura sequencial (offset contiguo) NAO cai aqui —
            // so seeks de verdade pagam o custo de REST+RETR. Ver comentario
            // no topo do arquivo.
            self.close_data_stream();
            self.open_data_stream(offset)?;
        }

        // Um unico Read::read() pode short-read (comportamento normal de
        // socket TCP) — sem o loop, isso devolvia bem menos do que o bloco
        // de 4-12MB que o PrefetchReader pediu, multiplicando refills.
        let stream = self.data.as_mut().expect("acabamos de abrir o stream");
        let mut total = 0usize;
        while total < buf.len() {
            let n = stream.read(&mut buf[total..])?;
            if n == 0 {
                self.data_exhausted = true;
                break;
            }
            total += n;
        }
        self.data_pos += total as u64;
        Ok(total)
    }
}

impl RangeSource for FtpFileSource {
    fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        match self.try_read_range(offset, buf) {
            Ok(n) => Ok(n),
            Err(_first_err) => {
                // Conexao provavelmente caiu (timeout/idle do servidor, doc
                // secao 6) — reconecta do zero uma vez e tenta a MESMA
                // leitura de novo antes de propagar o erro pro Demuxer.
                // Mesmo padrao de `SftpFileSource::read_range` (sftp/mod.rs).
                self.reconnect().map_err(io::Error::other)?;
                self.try_read_range(offset, buf)
            }
        }
    }

    fn len(&self) -> Option<u64> {
        Some(self.size)
    }
}

impl Drop for FtpFileSource {
    fn drop(&mut self) {
        // Fecha o RETR em andamento (se houver) de forma limpa antes de
        // derrubar a conexao de controle, pelo mesmo motivo do Drop de
        // `SmbFileSource`: nao deixar o servidor so descobrir via timeout.
        self.close_data_stream();
        let _ = self.control.quit();
    }
}
