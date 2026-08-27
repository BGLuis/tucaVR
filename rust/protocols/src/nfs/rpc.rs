//! Camada leve de serializacao XDR e cliente ONC RPC sobre TCP (RFC 1831 / RFC 5531).

use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Duration;

static XID_COUNTER: AtomicU32 = AtomicU32::new(100);

pub const PROG_PORTMAP: u32 = 100000;
pub const PROG_NFS: u32 = 100003;
pub const PROG_MOUNT: u32 = 100005;

pub const VERSION_3: u32 = 3;

// Procedimentos MOUNT v3
pub const MOUNTPROC3_MNT: u32 = 1;
pub const MOUNTPROC3_EXPORT: u32 = 5;

// Procedimentos NFS v3
pub const NFSPROC3_GETATTR: u32 = 1;
pub const NFSPROC3_LOOKUP: u32 = 3;
pub const NFSPROC3_READ: u32 = 6;
pub const NFSPROC3_READDIRPLUS: u32 = 16;
pub const NFSPROC3_READDIR: u32 = 17;

pub const AUTH_NONE: u32 = 0;
pub const AUTH_UNIX: u32 = 1;

/// Buffer XDR para serialização
#[derive(Default)]
pub struct XdrWriter {
    buf: Vec<u8>,
}

impl XdrWriter {
    pub fn new() -> Self {
        Self { buf: Vec::with_capacity(512) }
    }

    pub fn write_u32(&mut self, val: u32) {
        self.buf.extend_from_slice(&val.to_be_bytes());
    }

    pub fn write_u64(&mut self, val: u64) {
        self.buf.extend_from_slice(&val.to_be_bytes());
    }

    pub fn write_bytes(&mut self, data: &[u8]) {
        self.write_u32(data.len() as u32);
        self.buf.extend_from_slice(data);
        let pad = (4 - (data.len() % 4)) % 4;
        for _ in 0..pad {
            self.buf.push(0);
        }
    }

    pub fn write_string(&mut self, s: &str) {
        self.write_bytes(s.as_bytes());
    }

    pub fn write_raw(&mut self, data: &[u8]) {
        self.buf.extend_from_slice(data);
    }

    pub fn write_auth_unix(&mut self, uid: u32, gid: u32) {
        self.write_u32(AUTH_UNIX);
        let mut body = XdrWriter::new();
        body.write_u32(0); // stamp
        body.write_string("vrplayer"); // machinename
        body.write_u32(uid);
        body.write_u32(gid);
        body.write_u32(0); // gids count
        self.write_bytes(&body.into_bytes());

        // Verifier
        self.write_u32(AUTH_NONE);
        self.write_u32(0);
    }

    pub fn into_bytes(self) -> Vec<u8> {
        self.buf
    }
}

/// Leitor XDR para deserialização
pub struct XdrReader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> XdrReader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    pub fn pos(&self) -> usize {
        self.pos
    }

    pub fn remaining(&self) -> usize {
        self.buf.len().saturating_sub(self.pos)
    }

    pub fn skip(&mut self, bytes: usize) {
        self.pos = (self.pos + bytes).min(self.buf.len());
    }

    pub fn read_fixed_bytes<const N: usize>(&mut self) -> io::Result<[u8; N]> {
        if self.remaining() < N {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "XDR fixed bytes EOF"));
        }
        let mut arr = [0u8; N];
        arr.copy_from_slice(&self.buf[self.pos..self.pos + N]);
        self.pos += N;
        Ok(arr)
    }

    pub fn read_remaining(&self) -> &'a [u8] {
        &self.buf[self.pos..]
    }

    pub fn read_u32(&mut self) -> io::Result<u32> {
        if self.remaining() < 4 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "XDR u32 EOF"));
        }
        let val = u32::from_be_bytes(self.buf[self.pos..self.pos + 4].try_into().unwrap());
        self.pos += 4;
        Ok(val)
    }

    pub fn read_u64(&mut self) -> io::Result<u64> {
        if self.remaining() < 8 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "XDR u64 EOF"));
        }
        let val = u64::from_be_bytes(self.buf[self.pos..self.pos + 8].try_into().unwrap());
        self.pos += 8;
        Ok(val)
    }

    pub fn read_bytes(&mut self) -> io::Result<&'a [u8]> {
        let len = self.read_u32()? as usize;
        if self.remaining() < len {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "XDR bytes EOF"));
        }
        let slice = &self.buf[self.pos..self.pos + len];
        let pad = (4 - (len % 4)) % 4;
        self.pos += len + pad;
        Ok(slice)
    }

    pub fn read_string(&mut self) -> io::Result<String> {
        let bytes = self.read_bytes()?;
        String::from_utf8(bytes.to_vec())
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))
    }
}

/// Cliente RPC TCP síncrono
pub struct RpcClient {
    stream: TcpStream,
}

impl RpcClient {
    pub fn connect(host: &str, port: u16, timeout: Duration) -> Result<Self, String> {
        let addrs: Vec<SocketAddr> = (host, port)
            .to_socket_addrs()
            .map_err(|e| format!("Falha ao resolver {host}:{port}: {e}"))?
            .collect();

        if addrs.is_empty() {
            return Err(format!("Nenhum endereco encontrado para {host}:{port}"));
        }

        let stream = TcpStream::connect_timeout(&addrs[0], timeout)
            .map_err(|e| format!("Falha ao conectar em {host}:{port}: {e}"))?;

        stream
            .set_read_timeout(Some(timeout))
            .map_err(|e| e.to_string())?;
        stream
            .set_write_timeout(Some(timeout))
            .map_err(|e| e.to_string())?;

        Ok(Self { stream })
    }

    pub fn call(
        &mut self,
        program: u32,
        version: u32,
        procedure: u32,
        args: &[u8],
    ) -> Result<Vec<u8>, String> {
        let xid = XID_COUNTER.fetch_add(1, Ordering::Relaxed);

        let mut msg = XdrWriter::new();
        msg.write_u32(xid);
        msg.write_u32(0); // 0 = CALL
        msg.write_u32(2); // RPC Version 2
        msg.write_u32(program);
        msg.write_u32(version);
        msg.write_u32(procedure);
        msg.write_auth_unix(0, 0); // UID 0, GID 0 (root/anonimo padrao NFS)
        msg.buf.extend_from_slice(args);

        let payload = msg.into_bytes();
        let fragment_header = 0x80000000u32 | (payload.len() as u32);

        // Envia record mark + payload
        self.stream
            .write_all(&fragment_header.to_be_bytes())
            .map_err(|e| format!("Erro ao enviar cabecalho RPC: {e}"))?;
        self.stream
            .write_all(&payload)
            .map_err(|e| format!("Erro ao enviar payload RPC: {e}"))?;
        self.stream.flush().map_err(|e| e.to_string())?;

        // Recebe resposta
        let mut resp_header = [0u8; 4];
        self.stream
            .read_exact(&mut resp_header)
            .map_err(|e| format!("Erro ao ler resposta RPC: {e}"))?;

        let header_val = u32::from_be_bytes(resp_header);
        let resp_len = (header_val & 0x7FFFFFFF) as usize;

        let mut resp_buf = vec![0u8; resp_len];
        self.stream
            .read_exact(&mut resp_buf)
            .map_err(|e| format!("Erro ao ler payload de resposta RPC ({resp_len} bytes): {e}"))?;

        // Parse do header RPC
        let mut reader = XdrReader::new(&resp_buf);
        let rx_xid = reader.read_u32().map_err(|e| e.to_string())?;
        if rx_xid != xid {
            return Err(format!("XID incompativel (esperado {xid}, recebido {rx_xid})"));
        }

        let msg_type = reader.read_u32().map_err(|e| e.to_string())?;
        if msg_type != 1 {
            return Err("Resposta RPC com tipo invalido (esperado REPLY)".into());
        }

        let reply_stat = reader.read_u32().map_err(|e| e.to_string())?;
        if reply_stat != 0 {
            return Err("RPC call rejeitado pelo servidor".into());
        }

        // Verifier
        let _verf_flavor = reader.read_u32().map_err(|e| e.to_string())?;
        let _verf_bytes = reader.read_bytes().map_err(|e| e.to_string())?;

        let accept_stat = reader.read_u32().map_err(|e| e.to_string())?;
        if accept_stat != 0 {
            return Err(format!("RPC accepted com status de erro: {accept_stat}"));
        }

        // Retorna o restante dos bytes (payload especifico do procedimento)
        Ok(resp_buf[reader.pos..].to_vec())
    }
}
