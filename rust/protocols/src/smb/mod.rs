//! Cliente SMB2/3 (T6.1) construido sobre a crate `smb2` (pura Rust, sem
//! `libsmbclient`/dependencias C — este `libavformat.so` nem tem
//! `CONFIG_LIBSMBCLIENT` habilitado, entao custom I/O era inevitavel de
//! qualquer forma; ver `crate::http` para o achado equivalente do lado
//! HTTPS). A crate `smb2` cobre: negociacao SMB 2.x/3.x, autenticacao NTLMv2
//! (usuario/senha OU guest/anonimo com `username` vazio), assinatura de
//! mensagens, e reconexao automatica (`ClientConfig::auto_reconnect`) — o
//! que satisfaz o aviso do doc (secao 6) sobre timeout/reconexao em Wi-Fi
//! instavel do Quest sem eu precisar reimplementar isso na mao.
//!
//! **O que NAO esta implementado** (fora do escopo desta sessao,
//! documentado honestamente em vez de fingido): escrita de arquivos,
//! descoberta automatica de servidores (NetBIOS/mDNS — T6.2, ver
//! `docs/phases/PHASE-0.1-MVP.md`), Kerberos (so NTLM). SMB1 nao e uma
//! omissao — a propria crate `smb2` so fala SMB2/3, o que e o comportamento
//! correto (SMB1 esta obsoleto e inseguro).
//!
//! A crate `smb2` e inteiramente `async`/tokio; o FFmpeg custom I/O exige
//! callbacks SINCRONOS rodando na thread que o esta chamando (ver docs do
//! `StreamIo` do `ffmpeg-next`, `format::input_from_stream`). Cada
//! `SmbFileSource`/chamada de listagem aqui cria seu proprio
//! `tokio::runtime::Runtime` (current-thread) e usa `block_on` para ligar as
//! duas pontas — nao ha um runtime global compartilhado.

pub mod uri;

pub use uri::{SmbTarget, is_smb_uri, redact};

use crate::chunking::split_range;
use crate::prefetch::RangeSource;
use futures_util::future::join_all;
use smb2::{ClientConfig, SmbClient};
use std::io;
use std::time::Duration;
use tokio::runtime::Runtime;

/// Tamanho de cada sub-leitura concorrente dentro de um `read_range` (T6.3,
/// otimizacao desta sessao — ver `crate::chunking`). `FileReader::read_at`
/// toma `&self` e ja e seguro chamar concorrentemente sobre o mesmo reader —
/// a doc da propria crate confirma que isso pipeline sobre a UNICA sessao
/// SMB (multiplexado por `MessageId`), entao dividir um bloco de 4-12MB do
/// `PrefetchReader` em varias chamadas de 1MB em voo ao mesmo tempo, em vez
/// de uma so, usa a banda disponivel em vez de serializar RTT por RTT. Nao
/// tentamos descobrir o `MaxReadSize` negociado (privado na crate) — se for
/// menor que 1MB, `read_at` so faz sua propria sub-divisao sequencial interna
/// dentro de cada chamada concorrente, sem quebrar nada.
const SMB_CONCURRENT_CHUNK_SIZE: u32 = 1024 * 1024;

/// Quantas sub-leituras concorrentes no maximo por vez — mesmo raciocinio e
/// mesmo valor do `SFTP_MAX_CONCURRENT_CHUNKS` (ver `crate::sftp`): um
/// disparo sem limite (todos os chunks do bloco de uma vez) coincidiu com uma
/// regressao de desempenho medida no Quest 3 no caminho SFTP irmao deste;
/// limitar reduz tanto a contencao de CPU com a thread de decode quanto o
/// risco de estourar algum limite do lado do servidor.
const SMB_MAX_CONCURRENT_CHUNKS: usize = 4;

/// Teto por lote de leitura — mesmo risco e mesmo valor do `SFTP_READ_TIMEOUT` (`crate::sftp`): sem isto, um `read_at` sem resposta trava `block_on` pra sempre e prende `seek()`/`stop()` no `join()` da thread.
const SMB_READ_TIMEOUT: Duration = Duration::from_secs(20);

fn new_runtime() -> io::Result<Runtime> {
    tokio::runtime::Builder::new_current_thread().enable_all().build()
}

fn client_config(t: &SmbTarget, auto_reconnect: bool) -> ClientConfig {
    ClientConfig {
        addr: format!("{}:{}", t.host, t.port),
        timeout: Duration::from_secs(8),
        username: t.username.clone(),
        password: t.password.clone(),
        domain: t.domain.clone(),
        auto_reconnect,
        compression: true,
        dfs_enabled: true,
        dfs_target_overrides: Default::default(),
    }
}

/// Lista os shares de um servidor (T6.1 "listar shares") — tambem serve como
/// "testar conexao"/"status de conexao" para a UI (T6.4): se autenticar e
/// listar funcionam, a conexao esta OK. Conexao curta, sem auto-reconnect
/// (nao vale a pena para uma chamada de UI de poucos segundos).
pub fn list_shares(t: &SmbTarget) -> Result<Vec<String>, String> {
    let rt = new_runtime().map_err(|e| e.to_string())?;
    rt.block_on(async {
        let mut client = SmbClient::connect(client_config(t, false)).await.map_err(|e| e.to_string())?;
        let shares = client.list_shares().await.map_err(|e| e.to_string())?;
        Ok(shares.into_iter().map(|s| s.name).collect())
    })
}

pub struct SmbDirEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
}

/// Lista um diretorio dentro de um share (T6.1 "navegar diretorios").
/// `path` vazio = raiz do share.
pub fn list_directory(t: &SmbTarget, path: &str) -> Result<Vec<SmbDirEntry>, String> {
    let rt = new_runtime().map_err(|e| e.to_string())?;
    rt.block_on(async {
        let mut client = SmbClient::connect(client_config(t, false)).await.map_err(|e| e.to_string())?;
        let mut tree = client.connect_share(&t.share).await.map_err(|e| e.to_string())?;
        let entries = client.list_directory(&mut tree, path).await.map_err(|e| e.to_string())?;
        let _ = client.disconnect_share(&tree).await;
        Ok(entries
            .into_iter()
            .map(|e| SmbDirEntry { name: e.name, is_dir: e.is_directory, size: e.size })
            .collect())
    })
}

/// Fonte de leitura posicional para o Demuxer via custom I/O (T6.3 —
/// integracao SMB<->Demuxer; SMB nao tem um verbo "seek" separado, um READ
/// com offset explicito JA E o primitivo de seek, como a tarefa descreve).
/// Mantem uma conexao/handle de arquivo abertos pelo tempo de vida do
/// `PrefetchReader` que envolve isto. `auto_reconnect` fica ligado aqui
/// (diferente das chamadas de UI acima, que sao curtas): playback pode durar
/// horas e o Wi-Fi do Quest cai (doc, secao 6, aviso "Timeout e reconexao").
pub struct SmbFileSource {
    runtime: Runtime,
    reader: Option<smb2::client::stream::FileReader>,
    size: u64,
}

impl SmbFileSource {
    pub fn open(t: &SmbTarget) -> Result<Self, String> {
        let runtime = new_runtime().map_err(|e| e.to_string())?;
        let reader = runtime.block_on(async {
            let mut client = SmbClient::connect(client_config(t, true)).await.map_err(|e| e.to_string())?;
            let tree = client.connect_share(&t.share).await.map_err(|e| e.to_string())?;
            client.open_file_reader(&tree, &t.path).await.map_err(|e| e.to_string())
        })?;
        let size = reader.size();
        Ok(Self { runtime, reader: Some(reader), size })
    }
}

impl RangeSource for SmbFileSource {
    fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        let Some(reader) = self.reader.as_ref() else {
            return Err(io::Error::other("SmbFileSource ja fechado"));
        };
        let want = if offset >= self.size { 0 } else { (buf.len() as u64).min(self.size - offset) as u32 };
        if want == 0 {
            return Ok(0);
        }

        let chunks = split_range(offset, want, SMB_CONCURRENT_CHUNK_SIZE);
        let mut total = 0usize;
        'batches: for batch in chunks.chunks(SMB_MAX_CONCURRENT_CHUNKS) {
            let results = match self.runtime.block_on(tokio::time::timeout(
                SMB_READ_TIMEOUT,
                join_all(batch.iter().map(|&(chunk_offset, chunk_len)| reader.read_at(chunk_offset, chunk_len as u64))),
            )) {
                Ok(results) => results,
                Err(_elapsed) => {
                    return Err(io::Error::new(
                        io::ErrorKind::TimedOut,
                        format!("SMB: sem resposta do servidor por {}s (leitura travada)", SMB_READ_TIMEOUT.as_secs()),
                    ));
                }
            };

            for (idx, result) in results.into_iter().enumerate() {
                let data = result.map_err(|e| io::Error::other(e.to_string()))?;
                let (chunk_offset, chunk_len) = batch[idx];
                let start_in_buf = (chunk_offset - offset) as usize;
                let n = data.len();
                buf[start_in_buf..start_in_buf + n].copy_from_slice(&data[..n]);
                total += n;
                if n < chunk_len as usize {
                    // Curto por causa de EOF/truncamento concorrente no servidor
                    // (ja clampado contra `self.size` acima, entao isto so deve
                    // acontecer numa corrida real) — os chunks seguintes, se
                    // houver, nao formam um prefixo contiguo confiavel; para.
                    break 'batches;
                }
            }
        }
        Ok(total)
    }

    fn len(&self) -> Option<u64> {
        Some(self.size)
    }
}

impl Drop for SmbFileSource {
    fn drop(&mut self) {
        // Fecha o handle no servidor de forma limpa em vez de so deixar a
        // conexao cair (que tambem libera o handle, mas so depois do timeout
        // de lease do servidor).
        if let Some(reader) = self.reader.take() {
            let _ = self.runtime.block_on(reader.close());
        }
    }
}
