//! Operacoes de protocolo NFS v3 (Mount, Export, Lookup, Read, Readdir).

use super::rpc::*;
use std::time::Duration;

const NFS3_OK: u32 = 0;
const NF3DIR: u32 = 2;

#[derive(Debug, Clone)]
pub struct NfsDirEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
}

pub struct NfsClient {
    rpc: RpcClient,
}

impl NfsClient {
    pub fn connect(host: &str, port: u16, timeout: Duration) -> Result<Self, String> {
        let rpc = RpcClient::connect(host, port, timeout)?;
        Ok(Self { rpc })
    }

    /// Executa MOUNT v3 para obter o file handle raiz do export
    pub fn mount(&mut self, export_path: &str) -> Result<Vec<u8>, String> {
        let mut args = XdrWriter::new();
        args.write_string(export_path);

        let resp_bytes = self.rpc.call(PROG_MOUNT, VERSION_3, MOUNTPROC3_MNT, &args.into_bytes())?;
        let mut reader = XdrReader::new(&resp_bytes);
        let status = reader.read_u32().map_err(|e| e.to_string())?;
        if status != 0 {
            return Err(format!("MOUNT falhou para {export_path} com status {status}"));
        }

        let handle = reader.read_bytes().map_err(|e| e.to_string())?;
        Ok(handle.to_vec())
    }

    /// Lista os exports disponíveis no servidor (MOUNTPROC3_EXPORT)
    pub fn list_exports(&mut self) -> Result<Vec<String>, String> {
        let resp_bytes = self.rpc.call(PROG_MOUNT, VERSION_3, MOUNTPROC3_EXPORT, &[])?;
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

    /// Procura um arquivo ou pasta dentro de um diretorio (LOOKUP)
    pub fn lookup(&mut self, dir_handle: &[u8], name: &str) -> Result<(Vec<u8>, u64, bool), String> {
        let mut args = XdrWriter::new();
        args.write_bytes(dir_handle);
        args.write_string(name);

        let resp_bytes = self.rpc.call(PROG_NFS, VERSION_3, NFSPROC3_LOOKUP, &args.into_bytes())?;
        let mut reader = XdrReader::new(&resp_bytes);
        let status = reader.read_u32().map_err(|e| e.to_string())?;
        if status != NFS3_OK {
            return Err(format!("NFS LOOKUP falhou para '{name}' com status {status}"));
        }

        let obj_handle = reader.read_bytes().map_err(|e| e.to_string())?.to_vec();

        // Parse de post_op_attr
        let has_attr = reader.read_u32().map_err(|e| e.to_string())?;
        let (size, is_dir) = if has_attr != 0 {
            let ftype = reader.read_u32().map_err(|e| e.to_string())?;
            let _mode = reader.read_u32().map_err(|e| e.to_string())?;
            let _nlink = reader.read_u32().map_err(|e| e.to_string())?;
            let _uid = reader.read_u32().map_err(|e| e.to_string())?;
            let _gid = reader.read_u32().map_err(|e| e.to_string())?;
            let size = reader.read_u64().map_err(|e| e.to_string())?;
            (size, ftype == NF3DIR)
        } else {
            (0, false)
        };

        Ok((obj_handle, size, is_dir))
    }

    /// Resolve um caminho hierarquico ("pasta/subpasta/arquivo.mkv") a partir do handle raiz
    pub fn resolve_path(&mut self, root_handle: &[u8], path: &str) -> Result<(Vec<u8>, u64, bool), String> {
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
            let (next_handle, size, is_dir) = self.lookup(&current_handle, seg)?;
            current_handle = next_handle;
            current_size = size;
            current_is_dir = is_dir;
        }

        Ok((current_handle, current_size, current_is_dir))
    }

    /// Lista os itens dentro de um diretório via READDIRPLUS ou READDIR
    pub fn readdir(&mut self, dir_handle: &[u8]) -> Result<Vec<NfsDirEntry>, String> {
        let mut entries = Vec::new();
        let mut cookie = 0u64;
        let mut cookieverf = [0u8; 8];
        let mut eof = false;

        while !eof {
            let mut args = XdrWriter::new();
            args.write_bytes(dir_handle);
            args.write_u64(cookie);
            args.write_raw(&cookieverf);
            args.write_u32(4096); // dir_count
            args.write_u32(32768); // max_count

            let resp_bytes = self.rpc.call(PROG_NFS, VERSION_3, NFSPROC3_READDIRPLUS, &args.into_bytes())
                .or_else(|_| {
                    // Fallback para READDIR basico
                    let mut basic_args = XdrWriter::new();
                    basic_args.write_bytes(dir_handle);
                    basic_args.write_u64(cookie);
                    basic_args.write_raw(&cookieverf);
                    basic_args.write_u32(32768);
                    self.rpc.call(PROG_NFS, VERSION_3, NFSPROC3_READDIR, &basic_args.into_bytes())
                })?;

            let mut reader = XdrReader::new(&resp_bytes);
            let status = reader.read_u32().map_err(|e| e.to_string())?;
            if status != NFS3_OK {
                return Err(format!("NFS READDIR falhou com status {status}"));
            }

            // post_op_attr do diretorio
            let has_dir_attr = reader.read_u32().map_err(|e| e.to_string())?;
            if has_dir_attr != 0 {
                // pula fattr3 (84 bytes)
                reader.skip(84);
            }

            // cookieverf
            if reader.remaining() >= 8 {
                cookieverf = reader.read_fixed_bytes::<8>().map_err(|e| e.to_string())?;
            }

            // Loop de entradas
            while reader.remaining() >= 4 {
                let value_follows = reader.read_u32().map_err(|e| e.to_string())?;
                if value_follows == 0 {
                    break;
                }

                let _fileid = reader.read_u64().map_err(|e| e.to_string())?;
                let name = reader.read_string().map_err(|e| e.to_string())?;
                cookie = reader.read_u64().map_err(|e| e.to_string())?;

                let mut size = 0u64;
                let mut is_dir = false;

                // Em READDIRPLUS temos atributos e handle inline
                if reader.remaining() >= 4 {
                    let has_name_attr = reader.read_u32().map_err(|e| e.to_string())?;
                    if has_name_attr != 0 && reader.remaining() >= 84 {
                        let ftype = reader.read_u32().map_err(|e| e.to_string())?;
                        reader.skip(20); // mode, nlink, uid, gid, etc.
                        size = reader.read_u64().map_err(|e| e.to_string())?;
                        reader.skip(56); // restante do fattr3
                        is_dir = ftype == NF3DIR;
                    }
                }

                if reader.remaining() >= 4 {
                    let has_handle = reader.read_u32().map_err(|e| e.to_string())?;
                    if has_handle != 0 {
                        let _ = reader.read_bytes();
                    }
                }

                if name != "." && name != ".." {
                    entries.push(NfsDirEntry { name, is_dir, size });
                }
            }

            eof = reader.read_u32().map_err(|e| e.to_string())? != 0;
        }

        Ok(entries)
    }

    /// Le um bloco de dados com offset posicional de 64 bits (READ)
    pub fn read(&mut self, file_handle: &[u8], offset: u64, count: u32) -> Result<(Vec<u8>, bool), String> {
        let mut args = XdrWriter::new();
        args.write_bytes(file_handle);
        args.write_u64(offset);
        args.write_u32(count);

        let resp_bytes = self.rpc.call(PROG_NFS, VERSION_3, NFSPROC3_READ, &args.into_bytes())?;
        let mut reader = XdrReader::new(&resp_bytes);
        let status = reader.read_u32().map_err(|e| e.to_string())?;
        if status != NFS3_OK {
            return Err(format!("NFS READ falhou com status {status} no offset {offset}"));
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
