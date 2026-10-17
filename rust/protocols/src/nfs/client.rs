//! Operacoes de protocolo NFS v3 (Mount, Export, Lookup, Read, Readdir).

use super::rpc::*;
use std::io;
use std::time::Duration;

const NFS3_OK: u32 = 0;
const NF3DIR: u32 = 2;

/// Tamanho de READ usado quando o servidor nao informa um (era o valor fixo anterior).
pub const DEFAULT_READ_SIZE: usize = 64 * 1024;
/// Teto do READ mesmo que o servidor aceite mais: um bloco maior so aumenta a latencia de cada RPC.
const MAX_READ_SIZE: usize = 1024 * 1024;

/// Tamanho de READ a usar dado o `rtmax` (maximo aceito) e o `rtpref` (preferido) do FSINFO.
fn choose_read_size(rtmax: u32, rtpref: u32) -> usize {
    let preferred = if rtpref > 0 { rtpref } else { rtmax };
    let capped = if rtmax > 0 {
        preferred.min(rtmax)
    } else {
        preferred
    };
    match (capped as usize).min(MAX_READ_SIZE) {
        0 => DEFAULT_READ_SIZE,
        size => size,
    }
}

#[derive(Debug, Clone)]
pub struct NfsDirEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub mtime: i64,
}

/// Atributos de `fattr3` (RFC 1813 §2.5) que o app usa.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Fattr3 {
    is_dir: bool,
    size: u64,
    mtime: i64,
}

/// Le um `fattr3` inteiro (84 bytes): type, mode, nlink, uid, gid (5 x u32), size, used,
/// rdev, fsid, fileid, atime, mtime, ctime (7 x 8 bytes depois do size).
fn read_fattr3(reader: &mut XdrReader) -> io::Result<Fattr3> {
    let ftype = reader.read_u32()?;
    reader.skip(16); // mode, nlink, uid, gid
    let size = reader.read_u64()?;
    reader.skip(40); // used, rdev, fsid, fileid, atime (5 x 8 bytes)
    let mtime = reader.read_u32()? as i64;
    reader.skip(12); // mtime.nseconds (4 bytes), ctime (8 bytes)
    Ok(Fattr3 {
        is_dir: ftype == NF3DIR,
        size,
        mtime,
    })
}

/// `post_op_attr`: um booleano seguido de `fattr3` quando verdadeiro.
fn read_post_op_attr(reader: &mut XdrReader) -> io::Result<Option<Fattr3>> {
    if reader.read_u32()? == 0 {
        return Ok(None);
    }
    read_fattr3(reader).map(Some)
}

/// Entrada de uma pagina de `READDIRPLUS` (`entryplus3`) ou `READDIR` (`entry3`); esta
/// ultima nao carrega atributos.
#[derive(Debug, PartialEq, Eq)]
struct RawEntry {
    name: String,
    cookie: u64,
    attrs: Option<Fattr3>,
}

#[derive(Debug, PartialEq, Eq)]
struct DirPage {
    cookieverf: [u8; 8],
    entries: Vec<RawEntry>,
    eof: bool,
}

/// Decodifica o corpo de uma resposta `READDIRPLUS3res` (`plus`) ou `READDIR3res`. O layout
/// das duas so difere dentro da entrada: `entryplus3` acrescenta `name_attributes` e
/// `name_handle` (RFC 1813 §3.3.16-3.3.17).
fn parse_dir_page(resp: &[u8], plus: bool) -> Result<DirPage, String> {
    let mut reader = XdrReader::new(resp);
    let io_err = |e: io::Error| e.to_string();

    let status = reader.read_u32().map_err(io_err)?;
    if status != NFS3_OK {
        return Err(format!("NFS READDIR falhou com status {status}"));
    }
    read_post_op_attr(&mut reader).map_err(io_err)?; // dir_attributes
    let cookieverf = reader.read_fixed_bytes::<8>().map_err(io_err)?;

    let mut entries = Vec::new();
    while reader.read_u32().map_err(io_err)? != 0 {
        let _fileid = reader.read_u64().map_err(io_err)?;
        let name = reader.read_string().map_err(io_err)?;
        let cookie = reader.read_u64().map_err(io_err)?;
        let mut attrs = None;
        if plus {
            attrs = read_post_op_attr(&mut reader).map_err(io_err)?;
            if reader.read_u32().map_err(io_err)? != 0 {
                reader.read_bytes().map_err(io_err)?; // name_handle
            }
        }
        entries.push(RawEntry {
            name,
            cookie,
            attrs,
        });
    }
    let eof = reader.read_u32().map_err(io_err)? != 0;

    Ok(DirPage {
        cookieverf,
        entries,
        eof,
    })
}

/// Consulta o `rpcbind` (portmap v2, RFC 1833) e devolve a porta TCP em que `program`
/// versao `version` esta registrado.
fn portmap_get_port(
    host: &str,
    portmap_port: u16,
    program: u32,
    version: u32,
    timeout: Duration,
) -> Result<u16, String> {
    let mut rpc = RpcClient::connect(host, portmap_port, timeout)?;
    let mut args = XdrWriter::new();
    args.write_u32(program);
    args.write_u32(version);
    args.write_u32(IPPROTO_TCP);
    args.write_u32(0); // port: ignorado na consulta
    let resp = rpc.call(
        PROG_PORTMAP,
        VERSION_2,
        PMAPPROC_GETPORT,
        &args.into_bytes(),
    )?;
    let port = XdrReader::new(&resp)
        .read_u32()
        .map_err(|e| e.to_string())?;
    u16::try_from(port).ok().filter(|&p| p != 0).ok_or_else(|| {
        format!(
            "rpcbind em {host}:{portmap_port} nao tem o programa {program} v{version} registrado"
        )
    })
}

/// Conexao com o `mountd`. O NFS fica na porta 2049 (RFC 1813 §2.3), mas o `mountd` de um
/// servidor Linux escuta numa porta propria, que so o `rpcbind` informa. Se o `rpcbind`
/// nao responde (firewall, NAS que expoe so a 2049), tenta o `mountd` na mesma porta do
/// NFS, que era o comportamento anterior.
fn connect_mount(
    host: &str,
    portmap_port: u16,
    nfs_port: u16,
    timeout: Duration,
) -> Result<RpcClient, String> {
    let mount_port = match portmap_get_port(host, portmap_port, PROG_MOUNT, VERSION_3, timeout) {
        Ok(port) => port,
        Err(e) => {
            log::info!(
                "NFS: sem porta do mountd via rpcbind ({e}); tentando a porta do NFS ({nfs_port})"
            );
            nfs_port
        }
    };
    RpcClient::connect(host, mount_port, timeout)
}

/// Executa MOUNT v3 e devolve o file handle raiz do export.
fn mount_export(rpc: &mut RpcClient, export_path: &str) -> Result<Vec<u8>, String> {
    let mut args = XdrWriter::new();
    args.write_string(export_path);

    let resp_bytes = rpc.call(PROG_MOUNT, VERSION_3, MOUNTPROC3_MNT, &args.into_bytes())?;
    let mut reader = XdrReader::new(&resp_bytes);
    let status = reader.read_u32().map_err(|e| e.to_string())?;
    if status != 0 {
        return Err(format!(
            "MOUNT falhou para {export_path} com status {status}"
        ));
    }

    let handle = reader.read_bytes().map_err(|e| e.to_string())?;
    Ok(handle.to_vec())
}

/// Lista os exports disponiveis no servidor (MOUNTPROC3_EXPORT).
pub fn list_exports(host: &str, nfs_port: u16, timeout: Duration) -> Result<Vec<String>, String> {
    list_exports_via(host, PORTMAP_PORT, nfs_port, timeout)
}

fn list_exports_via(
    host: &str,
    portmap_port: u16,
    nfs_port: u16,
    timeout: Duration,
) -> Result<Vec<String>, String> {
    let mut rpc = connect_mount(host, portmap_port, nfs_port, timeout)?;
    let resp_bytes = rpc.call(PROG_MOUNT, VERSION_3, MOUNTPROC3_EXPORT, &[])?;
    let mut reader = XdrReader::new(&resp_bytes);
    let mut exports = Vec::new();

    while reader.remaining() >= 4 {
        let has_item = reader.read_u32().map_err(|e| e.to_string())?;
        if has_item == 0 {
            break;
        }
        let dir = reader.read_string().map_err(|e| e.to_string())?;
        // Pula a lista de grupos autorizados
        while reader.remaining() >= 4 {
            let has_group = reader.read_u32().map_err(|e| e.to_string())?;
            if has_group == 0 {
                break;
            }
            let _grp = reader.read_string().map_err(|e| e.to_string())?;
        }
        exports.push(dir);
    }

    Ok(exports)
}

pub struct NfsClient {
    rpc: RpcClient,
}

impl NfsClient {
    /// Monta `export_path` (no `mountd`, achado pelo `rpcbind`) e devolve o cliente NFS
    /// conectado na `nfs_port` junto do handle raiz.
    pub fn open_export(
        host: &str,
        nfs_port: u16,
        export_path: &str,
        timeout: Duration,
    ) -> Result<(Self, Vec<u8>), String> {
        Self::open_export_via(host, PORTMAP_PORT, nfs_port, export_path, timeout)
    }

    fn open_export_via(
        host: &str,
        portmap_port: u16,
        nfs_port: u16,
        export_path: &str,
        timeout: Duration,
    ) -> Result<(Self, Vec<u8>), String> {
        let root_handle = {
            let mut mount_rpc = connect_mount(host, portmap_port, nfs_port, timeout)?;
            mount_export(&mut mount_rpc, export_path)?
        };
        let rpc = RpcClient::connect(host, nfs_port, timeout)?;
        Ok((Self { rpc }, root_handle))
    }

    /// Tamanho de READ que o servidor prefere (FSINFO, RFC 1813 §3.3.19), limitado a 1 MiB. Servidores
    /// Linux costumam pedir 1 MiB; com 64 KiB cada READ pagava um RTT inteiro. Se o FSINFO falha, mantem
    /// o valor conservador de antes.
    pub fn read_size(&mut self, handle: &[u8]) -> usize {
        let mut args = XdrWriter::new();
        args.write_bytes(handle);

        let parse = |resp: &[u8]| -> io::Result<(u32, u32)> {
            let mut reader = XdrReader::new(resp);
            if reader.read_u32()? != NFS3_OK {
                return Err(io::Error::other("FSINFO recusado"));
            }
            read_post_op_attr(&mut reader)?;
            Ok((reader.read_u32()?, reader.read_u32()?)) // rtmax, rtpref
        };

        match self
            .rpc
            .call(PROG_NFS, VERSION_3, NFSPROC3_FSINFO, &args.into_bytes())
        {
            Ok(resp) => match parse(&resp) {
                Ok((rtmax, rtpref)) => choose_read_size(rtmax, rtpref),
                Err(_) => DEFAULT_READ_SIZE,
            },
            Err(_) => DEFAULT_READ_SIZE,
        }
    }

    /// Procura um arquivo ou pasta dentro de um diretorio (LOOKUP)
    pub fn lookup(
        &mut self,
        dir_handle: &[u8],
        name: &str,
    ) -> Result<(Vec<u8>, u64, bool, i64), String> {
        let mut args = XdrWriter::new();
        args.write_bytes(dir_handle);
        args.write_string(name);

        let resp_bytes = self
            .rpc
            .call(PROG_NFS, VERSION_3, NFSPROC3_LOOKUP, &args.into_bytes())?;
        let mut reader = XdrReader::new(&resp_bytes);
        let status = reader.read_u32().map_err(|e| e.to_string())?;
        if status != NFS3_OK {
            return Err(format!(
                "NFS LOOKUP falhou para '{name}' com status {status}"
            ));
        }

        let obj_handle = reader.read_bytes().map_err(|e| e.to_string())?.to_vec();

        let attrs = read_post_op_attr(&mut reader).map_err(|e| e.to_string())?;
        let (size, is_dir, mtime) = attrs.map_or((0, false, 0), |a| (a.size, a.is_dir, a.mtime));

        Ok((obj_handle, size, is_dir, mtime))
    }

    /// Resolve um caminho hierarquico ("pasta/subpasta/arquivo.mkv") a partir do handle raiz
    pub fn resolve_path(
        &mut self,
        root_handle: &[u8],
        path: &str,
    ) -> Result<(Vec<u8>, u64, bool), String> {
        let segments: Vec<&str> = path
            .split('/')
            .map(|s| s.trim())
            .filter(|s| !s.is_empty() && *s != ".")
            .collect();

        if segments.is_empty() {
            return Ok((root_handle.to_vec(), 0, true));
        }

        let mut current_handle = root_handle.to_vec();
        let mut current_size = 0;
        let mut current_is_dir = true;

        for seg in segments {
            let (next_handle, size, is_dir, _) = self.lookup(&current_handle, seg)?;
            current_handle = next_handle;
            current_size = size;
            current_is_dir = is_dir;
        }

        Ok((current_handle, current_size, current_is_dir))
    }

    /// Lista os itens dentro de um diretório via READDIRPLUS, caindo para READDIR quando o
    /// servidor nao o suporta. O READDIR nao traz atributos, e o servidor tambem pode omiti-los
    /// no READDIRPLUS: nesses casos o tipo e o tamanho vem de um LOOKUP por entrada.
    pub fn readdir(&mut self, dir_handle: &[u8]) -> Result<Vec<NfsDirEntry>, String> {
        let mut entries = Vec::new();
        let mut cookie = 0u64;
        let mut cookieverf = [0u8; 8];
        let mut use_plus = true;

        loop {
            let mut args = XdrWriter::new();
            args.write_bytes(dir_handle);
            args.write_u64(cookie);
            args.write_raw(&cookieverf);
            let proc = if use_plus {
                args.write_u32(4096); // dircount
                args.write_u32(32768); // maxcount
                NFSPROC3_READDIRPLUS
            } else {
                args.write_u32(32768); // count
                NFSPROC3_READDIR
            };

            let resp = self.rpc.call(PROG_NFS, VERSION_3, proc, &args.into_bytes());
            let page = match resp.and_then(|bytes| parse_dir_page(&bytes, use_plus)) {
                Ok(page) => page,
                Err(_) if use_plus => {
                    use_plus = false;
                    continue;
                }
                Err(e) => return Err(e),
            };

            let page_len = page.entries.len();
            for raw in page.entries {
                cookie = raw.cookie;
                if raw.name == "." || raw.name == ".." {
                    continue;
                }
                let attrs = match raw.attrs {
                    Some(attrs) => attrs,
                    None => {
                        let (_, size, is_dir, mtime) = self.lookup(dir_handle, &raw.name)?;
                        Fattr3 {
                            is_dir,
                            size,
                            mtime,
                        }
                    }
                };
                entries.push(NfsDirEntry {
                    name: raw.name,
                    is_dir: attrs.is_dir,
                    size: attrs.size,
                    mtime: attrs.mtime,
                });
            }
            cookieverf = page.cookieverf;

            if page.eof {
                return Ok(entries);
            }
            if page_len == 0 {
                return Err(
                    "NFS READDIR devolveu uma pagina vazia sem sinalizar o fim da lista"
                        .to_string(),
                );
            }
        }
    }

    /// Le um bloco de dados com offset posicional de 64 bits (READ)
    pub fn read(
        &mut self,
        file_handle: &[u8],
        offset: u64,
        count: u32,
    ) -> Result<(Vec<u8>, bool), String> {
        let mut args = XdrWriter::new();
        args.write_bytes(file_handle);
        args.write_u64(offset);
        args.write_u32(count);

        let resp_bytes = self
            .rpc
            .call(PROG_NFS, VERSION_3, NFSPROC3_READ, &args.into_bytes())?;
        let mut reader = XdrReader::new(&resp_bytes);
        let status = reader.read_u32().map_err(|e| e.to_string())?;
        if status != NFS3_OK {
            return Err(format!(
                "NFS READ falhou com status {status} no offset {offset}"
            ));
        }

        // post_op_attr
        let has_attr = reader.read_u32().map_err(|e| e.to_string())?;
        if has_attr != 0 {
            reader.skip(84);
        }

        let _read_count = reader.read_u32().map_err(|e| e.to_string())?;
        let eof = reader.read_u32().map_err(|e| e.to_string())? != 0;
        let data = reader.read_bytes().map_err(|e| e.to_string())?;

        Ok((data.to_vec(), eof))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::thread;

    const T: Duration = Duration::from_secs(2);

    /// (programa, procedimento, argumentos) -> corpo da resposta.
    type RpcHandler = dyn Fn(u32, u32, &[u8]) -> Vec<u8> + Send + Sync;

    // --- respostas XDR montadas a mao a partir da RFC 1813 ---

    /// `fattr3` de 84 bytes; os campos que o app nao le ficam com um valor reconhecivel para que um
    /// salto errado no parser corrompa o que vem depois.
    fn fattr3(ftype: u32, size: u64) -> Vec<u8> {
        let mut w = XdrWriter::new();
        w.write_u32(ftype);
        for marker in [0o644u32, 1, 1000, 1000] {
            w.write_u32(marker); // mode, nlink, uid, gid
        }
        w.write_u64(size);
        for marker in 0..5u64 {
            w.write_u64(0xAAAA_0000 + marker); // used, rdev, fsid, fileid, atime
        }
        w.write_u32(1_700_000_000); // mtime.seconds
        w.write_u32(0); // mtime.nseconds
        w.write_u64(0xAAAA_0006); // ctime
        let bytes = w.into_bytes();
        assert_eq!(bytes.len(), 84);
        bytes
    }

    fn post_op_attr(attrs: Option<(u32, u64)>) -> Vec<u8> {
        let mut w = XdrWriter::new();
        match attrs {
            Some((ftype, size)) => {
                w.write_u32(1);
                w.write_raw(&fattr3(ftype, size));
            }
            None => w.write_u32(0),
        }
        w.into_bytes()
    }

    struct Entry<'a> {
        name: &'a str,
        cookie: u64,
        attrs: Option<(u32, u64)>,
        handle: Option<&'a [u8]>,
    }

    fn dir_response(plus: bool, entries: &[Entry], eof: bool) -> Vec<u8> {
        let mut w = XdrWriter::new();
        w.write_u32(NFS3_OK);
        w.write_raw(&post_op_attr(Some((NF3DIR, 4096)))); // dir_attributes
        w.write_raw(&[9, 8, 7, 6, 5, 4, 3, 2]); // cookieverf
        for e in entries {
            w.write_u32(1); // value follows
            w.write_u64(e.cookie + 1000); // fileid
            w.write_string(e.name);
            w.write_u64(e.cookie);
            if plus {
                w.write_raw(&post_op_attr(e.attrs));
                match e.handle {
                    Some(h) => {
                        w.write_u32(1);
                        w.write_bytes(h);
                    }
                    None => w.write_u32(0),
                }
            }
        }
        w.write_u32(0); // fim da lista
        w.write_u32(eof as u32);
        w.into_bytes()
    }

    #[test]
    fn readdirplus_page_decodes_names_types_and_sizes() {
        let resp = dir_response(
            true,
            &[
                Entry {
                    name: "filme.mkv",
                    cookie: 1,
                    attrs: Some((1, 7_000_000_000)),
                    handle: Some(&[1, 2, 3, 4, 5]),
                },
                Entry {
                    name: "legendas",
                    cookie: 2,
                    attrs: Some((NF3DIR, 4096)),
                    handle: Some(&[9; 8]),
                },
                Entry {
                    name: "sem-atributos.mp4",
                    cookie: 3,
                    attrs: None,
                    handle: None,
                },
            ],
            true,
        );

        let page = parse_dir_page(&resp, true).unwrap();

        assert_eq!(page.cookieverf, [9, 8, 7, 6, 5, 4, 3, 2]);
        assert!(page.eof);
        let summary: Vec<_> = page
            .entries
            .iter()
            .map(|e| (e.name.as_str(), e.cookie, e.attrs))
            .collect();
        assert_eq!(
            summary,
            vec![
                (
                    "filme.mkv",
                    1,
                    Some(Fattr3 {
                        is_dir: false,
                        size: 7_000_000_000,
                        mtime: 1_700_000_000,
                    })
                ),
                (
                    "legendas",
                    2,
                    Some(Fattr3 {
                        is_dir: true,
                        size: 4096,
                        mtime: 1_700_000_000,
                    })
                ),
                ("sem-atributos.mp4", 3, None),
            ]
        );
    }

    /// `entry3` nao tem `name_attributes` nem `name_handle`: o parser do READDIRPLUS leria os proximos
    /// bytes como atributos e desalinharia toda a lista.
    #[test]
    fn readdir_page_has_no_attributes_and_stays_aligned() {
        let resp = dir_response(
            false,
            &[
                Entry {
                    name: "a.mkv",
                    cookie: 10,
                    attrs: None,
                    handle: None,
                },
                Entry {
                    name: "b.mkv",
                    cookie: 20,
                    attrs: None,
                    handle: None,
                },
                Entry {
                    name: "c.mkv",
                    cookie: 30,
                    attrs: None,
                    handle: None,
                },
            ],
            false,
        );

        let page = parse_dir_page(&resp, false).unwrap();

        assert!(!page.eof);
        let summary: Vec<_> = page
            .entries
            .iter()
            .map(|e| (e.name.as_str(), e.cookie, e.attrs))
            .collect();
        assert_eq!(
            summary,
            vec![
                ("a.mkv", 10, None),
                ("b.mkv", 20, None),
                ("c.mkv", 30, None)
            ]
        );
    }

    #[test]
    fn dir_page_reports_server_status_and_truncation_as_errors() {
        let mut refused = XdrWriter::new();
        refused.write_u32(13); // NFS3ERR_ACCES
        assert!(
            parse_dir_page(&refused.into_bytes(), true)
                .unwrap_err()
                .contains("status 13")
        );

        let full = dir_response(
            true,
            &[Entry {
                name: "x.mkv",
                cookie: 1,
                attrs: Some((1, 5)),
                handle: None,
            }],
            true,
        );
        assert!(
            parse_dir_page(&full[..full.len() - 10], true).is_err(),
            "resposta truncada nao pode virar lista parcial"
        );
    }

    #[test]
    fn fattr3_reads_exactly_84_bytes() {
        let mut bytes = fattr3(NF3DIR, 123);
        bytes.extend_from_slice(&[0xEE; 4]); // sentinela logo depois do fattr3

        let mut reader = XdrReader::new(&bytes);
        let attrs = read_fattr3(&mut reader).unwrap();

        assert_eq!(
            attrs,
            Fattr3 {
                is_dir: true,
                size: 123,
                mtime: 1_700_000_000,
            }
        );
        assert_eq!(
            reader.remaining(),
            4,
            "o parser tem que parar no fim do fattr3"
        );
    }

    // --- servidor RPC falso em processo (portmap / mountd / nfs) ---

    /// Responde chamadas ONC RPC (TCP, record marking) com o corpo devolvido por `handler`, que recebe
    /// (programa, procedimento, argumentos). Devolve a porta em que escuta.
    fn fake_rpc_server(
        handler: impl Fn(u32, u32, &[u8]) -> Vec<u8> + Send + Sync + 'static,
    ) -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handler = std::sync::Arc::new(handler);
        thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let handler = handler.clone();
                thread::spawn(move || serve_connection(stream, &*handler));
            }
        });
        port
    }

    fn serve_connection(mut stream: TcpStream, handler: &RpcHandler) {
        loop {
            let mut mark = [0u8; 4];
            if stream.read_exact(&mut mark).is_err() {
                return;
            }
            let mut call = vec![0u8; (u32::from_be_bytes(mark) & 0x7FFF_FFFF) as usize];
            stream.read_exact(&mut call).unwrap();

            let mut r = XdrReader::new(&call);
            let xid = r.read_u32().unwrap();
            r.read_u32().unwrap(); // CALL
            r.read_u32().unwrap(); // rpc version
            let (program, _version, procedure) = (
                r.read_u32().unwrap(),
                r.read_u32().unwrap(),
                r.read_u32().unwrap(),
            );
            r.read_u32().unwrap(); // cred flavor
            r.read_bytes().unwrap();
            r.read_u32().unwrap(); // verf flavor
            r.read_bytes().unwrap();
            let body = handler(program, procedure, r.read_remaining());

            let mut reply = XdrWriter::new();
            reply.write_u32(xid);
            reply.write_u32(1); // REPLY
            reply.write_u32(0); // MSG_ACCEPTED
            reply.write_u32(0); // verf flavor
            reply.write_u32(0); // verf len
            reply.write_u32(0); // SUCCESS
            reply.write_raw(&body);
            let payload = reply.into_bytes();
            stream
                .write_all(&(0x8000_0000 | payload.len() as u32).to_be_bytes())
                .unwrap();
            stream.write_all(&payload).unwrap();
        }
    }

    fn mount_ok(handle: &[u8]) -> Vec<u8> {
        let mut w = XdrWriter::new();
        w.write_u32(0); // MNT3_OK
        w.write_bytes(handle);
        w.write_u32(0); // auth_flavors vazio
        w.into_bytes()
    }

    fn portmap_answering(mountd_port: u16) -> u16 {
        fake_rpc_server(move |program, procedure, args| {
            assert_eq!((program, procedure), (PROG_PORTMAP, PMAPPROC_GETPORT));
            let mut r = XdrReader::new(args);
            let asked = (
                r.read_u32().unwrap(),
                r.read_u32().unwrap(),
                r.read_u32().unwrap(),
            );
            assert_eq!(
                asked,
                (PROG_MOUNT, VERSION_3, IPPROTO_TCP),
                "tem que perguntar o MOUNT v3 sobre TCP"
            );
            let mut w = XdrWriter::new();
            w.write_u32(mountd_port as u32);
            w.into_bytes()
        })
    }

    fn free_port() -> u16 {
        TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port()
    }

    /// U-01: o `mountd` fica numa porta propria; so o rpcbind sabe qual. O NFS e o MOUNT respondem em
    /// portas diferentes e so o MOUNT sabe montar.
    #[test]
    fn mount_goes_to_the_port_the_rpcbind_reports_not_to_the_nfs_port() {
        let mountd = fake_rpc_server(|program, procedure, _| {
            assert_eq!((program, procedure), (PROG_MOUNT, MOUNTPROC3_MNT));
            mount_ok(&[0xAB; 12])
        });
        let nfsd = fake_rpc_server(|program, _, _| {
            panic!("o NFS nao deveria receber o programa {program} aqui")
        });
        let portmap = portmap_answering(mountd);

        let (_client, root) =
            NfsClient::open_export_via("127.0.0.1", portmap, nfsd, "/export", T).unwrap();

        assert_eq!(root, vec![0xAB; 12]);
    }

    #[test]
    fn falls_back_to_the_nfs_port_when_the_rpcbind_is_unreachable() {
        let both = fake_rpc_server(|_, _, _| mount_ok(&[0xCD; 8]));
        let no_rpcbind = free_port(); // nada escutando

        let (_client, root) =
            NfsClient::open_export_via("127.0.0.1", no_rpcbind, both, "/export", T).unwrap();

        assert_eq!(root, vec![0xCD; 8]);
    }

    #[test]
    fn falls_back_to_the_nfs_port_when_mount_is_not_registered() {
        let both = fake_rpc_server(|_, _, _| mount_ok(&[0xEF; 4]));
        let portmap = portmap_answering(0); // porta 0 = programa nao registrado

        let (_client, root) =
            NfsClient::open_export_via("127.0.0.1", portmap, both, "/export", T).unwrap();

        assert_eq!(root, vec![0xEF; 4]);
    }

    #[test]
    fn list_exports_also_asks_the_rpcbind_for_the_mountd_port() {
        let mountd = fake_rpc_server(|program, procedure, _| {
            assert_eq!((program, procedure), (PROG_MOUNT, MOUNTPROC3_EXPORT));
            let mut w = XdrWriter::new();
            w.write_u32(1);
            w.write_string("/export");
            w.write_u32(0); // sem grupos
            w.write_u32(0); // fim da lista
            w.into_bytes()
        });
        let portmap = portmap_answering(mountd);
        let nfs_port_with_no_mountd = free_port();

        let exports = list_exports_via("127.0.0.1", portmap, nfs_port_with_no_mountd, T).unwrap();

        assert_eq!(exports, vec!["/export".to_string()]);
    }

    fn lookup_ok(handle: &[u8], ftype: u32, size: u64) -> Vec<u8> {
        let mut w = XdrWriter::new();
        w.write_u32(NFS3_OK);
        w.write_bytes(handle);
        w.write_raw(&post_op_attr(Some((ftype, size)))); // obj_attributes
        w.write_raw(&post_op_attr(None)); // dir_attributes
        w.into_bytes()
    }

    /// Servidor sem READDIRPLUS: o cliente cai para READDIR, que nao traz tipo nem tamanho, e resolve
    /// cada entrada por LOOKUP. `.` e `..` nao aparecem no resultado.
    #[test]
    fn readdir_without_readdirplus_falls_back_and_resolves_types_by_lookup() {
        let calls = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let seen = calls.clone();
        let nfsd = fake_rpc_server(move |program, procedure, args| {
            assert_eq!(program, PROG_NFS);
            seen.lock().unwrap().push(procedure);
            match procedure {
                NFSPROC3_READDIRPLUS => {
                    let mut w = XdrWriter::new();
                    w.write_u32(10004); // NFS3ERR_NOTSUPP
                    w.write_u32(0);
                    w.into_bytes()
                }
                NFSPROC3_READDIR => dir_response(
                    false,
                    &[
                        Entry {
                            name: ".",
                            cookie: 1,
                            attrs: None,
                            handle: None,
                        },
                        Entry {
                            name: "..",
                            cookie: 2,
                            attrs: None,
                            handle: None,
                        },
                        Entry {
                            name: "filme.mkv",
                            cookie: 3,
                            attrs: None,
                            handle: None,
                        },
                        Entry {
                            name: "extras",
                            cookie: 4,
                            attrs: None,
                            handle: None,
                        },
                    ],
                    true,
                ),
                NFSPROC3_LOOKUP => {
                    let mut r = XdrReader::new(args);
                    r.read_bytes().unwrap(); // dir handle
                    match r.read_string().unwrap().as_str() {
                        "filme.mkv" => lookup_ok(&[1], 1, 7_000_000_000),
                        "extras" => lookup_ok(&[2], NF3DIR, 4096),
                        other => panic!("LOOKUP inesperado: {other}"),
                    }
                }
                other => panic!("procedimento inesperado: {other}"),
            }
        });
        let mut client = NfsClient {
            rpc: RpcClient::connect("127.0.0.1", nfsd, T).unwrap(),
        };

        let entries = client.readdir(&[0xDD; 8]).unwrap();

        let summary: Vec<_> = entries
            .iter()
            .map(|e| (e.name.as_str(), e.is_dir, e.size))
            .collect();
        assert_eq!(
            summary,
            vec![("filme.mkv", false, 7_000_000_000), ("extras", true, 4096)]
        );
        let calls = calls.lock().unwrap();
        assert_eq!(
            calls.iter().filter(|&&p| p == NFSPROC3_READDIRPLUS).count(),
            1,
            "READDIRPLUS e tentado uma vez so: {calls:?}"
        );
        assert!(calls.contains(&NFSPROC3_READDIR));
    }

    /// Uma listagem em varias paginas continua do cookie da ultima entrada e usa o cookieverf devolvido.
    #[test]
    fn readdir_follows_pages_until_eof() {
        let requested = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let seen = requested.clone();
        let nfsd = fake_rpc_server(move |_, procedure, args| {
            assert_eq!(procedure, NFSPROC3_READDIRPLUS);
            let mut r = XdrReader::new(args);
            r.read_bytes().unwrap();
            let cookie = r.read_u64().unwrap();
            let verf = r.read_fixed_bytes::<8>().unwrap();
            seen.lock().unwrap().push((cookie, verf));
            match cookie {
                0 => dir_response(
                    true,
                    &[Entry {
                        name: "a.mkv",
                        cookie: 11,
                        attrs: Some((1, 1)),
                        handle: None,
                    }],
                    false,
                ),
                11 => dir_response(
                    true,
                    &[Entry {
                        name: "b.mkv",
                        cookie: 22,
                        attrs: Some((1, 2)),
                        handle: None,
                    }],
                    true,
                ),
                other => panic!("cookie inesperado {other}"),
            }
        });
        let mut client = NfsClient {
            rpc: RpcClient::connect("127.0.0.1", nfsd, T).unwrap(),
        };

        let entries = client.readdir(&[0xDD; 8]).unwrap();

        assert_eq!(
            entries.iter().map(|e| e.name.as_str()).collect::<Vec<_>>(),
            vec!["a.mkv", "b.mkv"]
        );
        let requested = requested.lock().unwrap();
        assert_eq!(requested[0], (0, [0; 8]));
        assert_eq!(
            requested[1],
            (11, [9, 8, 7, 6, 5, 4, 3, 2]),
            "a 2a pagina devolve o cookieverf da 1a"
        );
    }

    #[test]
    fn read_size_follows_the_servers_preference_within_its_limits() {
        assert_eq!(
            choose_read_size(1 << 20, 1 << 20),
            1 << 20,
            "Linux costuma anunciar 1 MiB"
        );
        assert_eq!(
            choose_read_size(1 << 20, 32 * 1024),
            32 * 1024,
            "respeita um rtpref menor"
        );
        assert_eq!(
            choose_read_size(64 * 1024, 1 << 20),
            64 * 1024,
            "nunca acima do rtmax"
        );
        assert_eq!(
            choose_read_size(4 << 20, 4 << 20),
            1 << 20,
            "teto de 1 MiB mesmo que o servidor aceite mais"
        );
        assert_eq!(
            choose_read_size(1 << 20, 0),
            1 << 20,
            "sem rtpref, usa o rtmax"
        );
        assert_eq!(
            choose_read_size(0, 0),
            DEFAULT_READ_SIZE,
            "servidor que nao informa nada"
        );
    }

    fn fsinfo_response(rtmax: u32, rtpref: u32) -> Vec<u8> {
        let mut w = XdrWriter::new();
        w.write_u32(NFS3_OK);
        w.write_raw(&post_op_attr(Some((NF3DIR, 4096))));
        w.write_u32(rtmax);
        w.write_u32(rtpref);
        for _ in 0..5 {
            w.write_u32(0); // rtmult, wtmax, wtpref, wtmult, dtpref
        }
        w.into_bytes()
    }

    #[test]
    fn client_reads_the_preferred_size_from_fsinfo() {
        let nfsd = fake_rpc_server(|_, procedure, _| {
            assert_eq!(procedure, NFSPROC3_FSINFO);
            fsinfo_response(1 << 20, 512 * 1024)
        });
        let mut client = NfsClient {
            rpc: RpcClient::connect("127.0.0.1", nfsd, T).unwrap(),
        };

        assert_eq!(client.read_size(&[1; 8]), 512 * 1024);
    }

    #[test]
    fn client_keeps_the_conservative_size_when_fsinfo_fails() {
        let nfsd = fake_rpc_server(|_, _, _| {
            let mut w = XdrWriter::new();
            w.write_u32(10004); // NFS3ERR_NOTSUPP
            w.write_u32(0);
            w.into_bytes()
        });
        let mut client = NfsClient {
            rpc: RpcClient::connect("127.0.0.1", nfsd, T).unwrap(),
        };

        assert_eq!(client.read_size(&[1; 8]), DEFAULT_READ_SIZE);
    }
}
