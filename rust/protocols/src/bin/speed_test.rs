//! Ferramenta de linha de comando para medir a velocidade MAXIMA de leitura
//! de um servidor de rede (SMB/SFTP/FTP/NFS/WebDAV/HTTP(S)), usando os MESMOS
//! clientes `RangeSource` que o app usa em produção (via
//! `crate::download::default_source_opener` — o mesmo dispatcher usado pelo
//! Download Manager, ver `download/mod.rs`).
//!
//! Objetivo: isolar os dois lados do gargalo observado em sessoes de
//! playback com stalls longos (ver docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md
//! e NETWORK-IO-PERFORMANCE.md) — rodando SO a leitura de rede, sem decode/
//! render/OpenXR no caminho, da pra saber se um stall e:
//!   - rede/servidor lento de verdade (esta ferramenta tambem fica lenta/trava)
//!   - ou um gargalo em outro estagio do pipeline do app (esta ferramenta
//!     mantém throughput alto e estavel mesmo quando o playback trava)
//!
//! Roda em qualquer host (laptop/CI) — `protocols` nao tem dependencia de
//! NDK/Android (ver CLAUDE.md, secao Testing). NAO precisa do Quest 3.
//!
//! Uso:
//!   cargo run -p protocols --release --bin speed_test -- \
//!     --protocol sftp --host 10.10.10.44 --port 2022 \
//!     --user meuuser --password 'minhasenha' \
//!     --path "server/torrent/videos/arquivo.mp4" \
//!     --duration 30
//!
//! `--help` lista todos os protocolos e flags suportados.

use protocols::download::default_source_opener;
use protocols::ftp::FtpTarget;
use protocols::nfs::NfsTarget;
use protocols::sftp::SftpTarget;
use protocols::smb::SmbTarget;
use protocols::webdav::WebdavTarget;
use std::collections::HashMap;
use std::process::ExitCode;
use std::time::{Duration, Instant};

/// Igual a `REMOTE_PREFETCH_BLOCK_SIZE` em `core/src/demuxer.rs` — o tamanho
/// de bloco que o player de verdade usa pra sessoes remotas, pra que os
/// MB/s medidos aqui sejam comparaveis com a coluna `net_mbs` da telemetria
/// (`DebugTelemetryExporter.kt`) em vez de um numero de outra magnitude.
const DEFAULT_BLOCK_SIZE_MB: u64 = 12;

fn print_help() {
    eprintln!(
        r#"speed_test — mede a velocidade maxima de leitura de um servidor de rede,
usando o mesmo cliente RangeSource que o app usa em producao.

USO:
  speed_test --protocol <sftp|smb|ftp|nfs|webdav|webdavs|http|https> [flags]

FLAGS COMUNS:
  --path <caminho>         Caminho do arquivo remoto a ler (obrigatorio, exceto http/https)
  --duration <segundos>    Para a leitura apos N segundos (default: le o arquivo inteiro)
  --block-size-mb <n>      Tamanho do bloco de leitura em MB (default: {DEFAULT_BLOCK_SIZE_MB}, igual ao player)
  --host <host>            Endereco do servidor (nao usado em http/https, ver --url)
  --port <porta>           Porta (default por protocolo: sftp=22 smb=445 ftp=21 nfs=2049 webdav=80/webdavs=443)
  --user <usuario>         Usuario (default: vazio = anonimo/guest, quando suportado)
  --password <senha>       Senha (default: vazio)

SFTP:
  --path e relativo ao $HOME do usuario SSH (ou absoluto). Autenticacao por
  chave privada nao e suportada por esta ferramenta (so senha) — use um
  usuario/senha de teste.

SMB:
  --share <nome>           Nome do compartilhamento (obrigatorio)
  --domain <dominio>       Dominio (default: vazio = dominio local)

FTP:
  Login anonimo se --user/--password forem omitidos.

NFS:
  --nfs-version <2|3|4>    Versao do protocolo NFS (default: 3)
  Sem credenciais (NFS classico e trust-based por host).

WEBDAV / WEBDAVS:
  --base-path <caminho>    Prefixo base no servidor (ex: /remote.php/dav/files/user), default vazio
  --accept-invalid-certs   Aceita certificado TLS invalido/auto-assinado (so webdavs)

HTTP / HTTPS:
  --url <url>               URL completa do arquivo, credenciais embutidas se precisar
                             (ex: https://usuario:senha@host:porta/caminho/arquivo.mp4)

EXEMPLOS:
  speed_test --protocol sftp --host 10.10.10.44 --port 2022 \
    --user meuuser --password 'minhasenha' \
    --path "server/torrent/videos/arquivo.mp4" --duration 30

  speed_test --protocol https --url "https://10.10.10.44/videos/arquivo.mp4" --duration 30
"#
    );
}

struct Args {
    flags: HashMap<String, String>,
    bools: std::collections::HashSet<String>,
}

impl Args {
    fn parse() -> Self {
        let mut flags = HashMap::new();
        let mut bools = std::collections::HashSet::new();
        let mut it = std::env::args().skip(1).peekable();
        while let Some(arg) = it.next() {
            let Some(name) = arg.strip_prefix("--") else { continue };
            match name {
                "accept-invalid-certs" => {
                    bools.insert(name.to_string());
                }
                _ => {
                    if let Some(value) = it.next() {
                        flags.insert(name.to_string(), value);
                    } else {
                        eprintln!("speed_test: flag --{name} precisa de um valor");
                        std::process::exit(2);
                    }
                }
            }
        }
        Args { flags, bools }
    }

    fn get(&self, name: &str) -> Option<&str> {
        self.flags.get(name).map(String::as_str)
    }

    fn get_or(&self, name: &str, default: &str) -> String {
        self.get(name).unwrap_or(default).to_string()
    }

    fn require(&self, name: &str) -> String {
        match self.get(name) {
            Some(v) => v.to_string(),
            None => {
                eprintln!("speed_test: flag --{name} e obrigatoria para este protocolo (use --help)");
                std::process::exit(2);
            }
        }
    }

    fn get_u16(&self, name: &str, default: u16) -> u16 {
        self.get(name).map(|v| v.parse().unwrap_or_else(|_| {
            eprintln!("speed_test: --{name} invalido, esperado numero");
            std::process::exit(2);
        })).unwrap_or(default)
    }

    fn has_bool(&self, name: &str) -> bool {
        self.bools.contains(name)
    }
}

/// Monta a URI interna (NUL-separada, formato de `Target::to_internal`) a
/// partir das flags da linha de comando, para o protocolo pedido. Mesmo
/// formato que o app usa internamente (JNI/bridge) — ver comentario em
/// `sftp/uri.rs` sobre por que nao e uma URI RFC 3986 de verdade.
fn build_internal_uri(protocol: &str, args: &Args) -> String {
    match protocol {
        "sftp" => {
            let target = SftpTarget {
                host: args.require("host"),
                port: args.get_u16("port", 22),
                path: args.require("path"),
                username: args.get_or("user", ""),
                password: args.get_or("password", ""),
                private_key: None,
            };
            target.to_internal()
        }
        "smb" => {
            let target = SmbTarget {
                host: args.require("host"),
                port: args.get_u16("port", 445),
                share: args.require("share"),
                path: args.require("path"),
                username: args.get_or("user", ""),
                password: args.get_or("password", ""),
                domain: args.get_or("domain", ""),
            };
            target.to_internal()
        }
        "ftp" => {
            let target = FtpTarget {
                host: args.require("host"),
                port: args.get_u16("port", 21),
                path: args.require("path"),
                username: args.get_or("user", ""),
                password: args.get_or("password", ""),
            };
            target.to_internal()
        }
        "nfs" => {
            let version: u8 = args.get_or("nfs-version", "3").parse().unwrap_or_else(|_| {
                eprintln!("speed_test: --nfs-version invalido, esperado 2, 3 ou 4");
                std::process::exit(2);
            });
            let target = NfsTarget {
                host: args.require("host"),
                port: args.get_u16("port", 2049),
                export_path: args.get_or("base-path", ""),
                file_path: args.require("path"),
                version,
            };
            target.to_internal()
        }
        "webdav" | "webdavs" => {
            let use_https = protocol == "webdavs" || args.has_bool("https");
            let target = WebdavTarget {
                host: args.require("host"),
                port: args.get_u16("port", if use_https { 443 } else { 80 }),
                base_path: args.get_or("base-path", ""),
                file_path: args.require("path"),
                username: args.get_or("user", ""),
                password: args.get_or("password", ""),
                use_https,
                accept_invalid_certs: args.has_bool("accept-invalid-certs"),
            };
            target.to_internal()
        }
        "http" | "https" => args.require("url"),
        other => {
            eprintln!("speed_test: protocolo desconhecido '{other}' (use --help)");
            std::process::exit(2);
        }
    }
}

fn human_mb(bytes: u64) -> f64 {
    bytes as f64 / (1024.0 * 1024.0)
}

fn main() -> ExitCode {
    let raw_args: Vec<String> = std::env::args().collect();
    if raw_args.iter().any(|a| a == "--help" || a == "-h") || raw_args.len() < 2 {
        print_help();
        return ExitCode::from(if raw_args.len() < 2 { 2 } else { 0 });
    }

    let args = Args::parse();
    let protocol = args.require("protocol").to_lowercase();
    let duration_limit = args.get("duration").map(|v| {
        let secs: u64 = v.parse().unwrap_or_else(|_| {
            eprintln!("speed_test: --duration invalido, esperado numero de segundos");
            std::process::exit(2);
        });
        Duration::from_secs(secs)
    });
    let block_size = (args.get_or("block-size-mb", &DEFAULT_BLOCK_SIZE_MB.to_string()).parse::<u64>().unwrap_or(DEFAULT_BLOCK_SIZE_MB) * 1024 * 1024) as usize;

    let internal_uri = build_internal_uri(&protocol, &args);

    eprintln!("speed_test: abrindo conexao ({protocol})...");
    let open_start = Instant::now();
    let mut source = match default_source_opener(&internal_uri) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("speed_test: falha ao abrir conexao/arquivo: {e}");
            return ExitCode::FAILURE;
        }
    };
    eprintln!("speed_test: conexao aberta em {:.2}s", open_start.elapsed().as_secs_f64());

    let total_len = source.len();
    match total_len {
        Some(len) => eprintln!("speed_test: tamanho do arquivo: {} ({:.2} MB)", len, human_mb(len)),
        None => eprintln!("speed_test: tamanho do arquivo desconhecido (protocolo nao informou)"),
    }
    eprintln!("speed_test: lendo em blocos de {} MB...\n", block_size / (1024 * 1024));

    let mut buf = vec![0u8; block_size];
    let mut offset: u64 = 0;
    let mut total_read: u64 = 0;
    let mut block_num: u64 = 0;
    let mut min_mbs = f64::MAX;
    let mut max_mbs = 0.0f64;
    let run_start = Instant::now();

    loop {
        if let Some(limit) = duration_limit
            && run_start.elapsed() >= limit
        {
            eprintln!("speed_test: limite de --duration atingido, parando.");
            break;
        }
        if let Some(len) = total_len
            && offset >= len
        {
            eprintln!("speed_test: arquivo inteiro lido.");
            break;
        }

        let want = match total_len {
            Some(len) => block_size.min((len - offset) as usize),
            None => block_size,
        };

        let block_start = Instant::now();
        let n = match source.read_range(offset, &mut buf[..want]) {
            Ok(n) => n,
            Err(e) => {
                eprintln!(
                    "\nspeed_test: ERRO de leitura no offset {offset} apos {:.2}s de sessao ({:.2} MB lidos ate aqui): {e}",
                    run_start.elapsed().as_secs_f64(),
                    human_mb(total_read)
                );
                eprintln!("speed_test: isto e um sinal de stall/falha real de rede ou servidor — nao um artefato do pipeline de playback do app.");
                return ExitCode::FAILURE;
            }
        };
        let block_elapsed = block_start.elapsed();

        if n == 0 {
            eprintln!("speed_test: EOF no offset {offset}.");
            break;
        }

        block_num += 1;
        offset += n as u64;
        total_read += n as u64;

        let block_mbs = human_mb(n as u64) / block_elapsed.as_secs_f64().max(0.001);
        min_mbs = min_mbs.min(block_mbs);
        max_mbs = max_mbs.max(block_mbs);
        let avg_mbs = human_mb(total_read) / run_start.elapsed().as_secs_f64().max(0.001);

        eprintln!(
            "[{:>6.1}s] bloco #{block_num}: +{:.2} MB em {:.3}s => {:>7.2} MB/s   (media da sessao: {:>7.2} MB/s, total: {:.1} MB)",
            run_start.elapsed().as_secs_f64(),
            human_mb(n as u64),
            block_elapsed.as_secs_f64(),
            block_mbs,
            avg_mbs,
            human_mb(total_read),
        );

        if n < want {
            // Leitura parcial sem erro explicito: fim de dados legitimo (ver
            // comentario em sftp/mod.rs sobre "servidor devolveu menos sem
            // sinalizar EOF") — nao ha mais o que ler depois disto.
            eprintln!("speed_test: leitura parcial (fim de dados).");
            break;
        }
    }

    let total_elapsed = run_start.elapsed();
    let avg_mbs = human_mb(total_read) / total_elapsed.as_secs_f64().max(0.001);

    eprintln!("\n=== resumo ===");
    eprintln!("protocolo:        {protocol}");
    eprintln!("total lido:       {:.2} MB ({block_num} blocos)", human_mb(total_read));
    eprintln!("tempo total:      {:.2}s", total_elapsed.as_secs_f64());
    eprintln!("throughput medio: {avg_mbs:.2} MB/s");
    if block_num > 0 {
        eprintln!("throughput min/max por bloco: {min_mbs:.2} / {max_mbs:.2} MB/s");
    }
    eprintln!(
        "\nCompare com a coluna net_mbs do CSV de telemetria (DebugTelemetryExporter) durante\n\
         playback do MESMO arquivo neste MESMO servidor: se este numero fica alto e estavel\n\
         mas o playback no headset trava, o gargalo esta no pipeline do app (decode/fila/\n\
         render), nao na rede/servidor. Se este numero tambem cai/trava, o gargalo e o lado\n\
         da rede/servidor."
    );

    ExitCode::SUCCESS
}
