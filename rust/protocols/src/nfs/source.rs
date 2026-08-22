//! Fonte de dados para streaming NFS implementando `RangeSource` para o `PrefetchReader`.

use super::client::NfsClient;
use super::uri::NfsTarget;
use crate::prefetch::RangeSource;
use std::io;
use std::time::Duration;

const NFS_TIMEOUT: Duration = Duration::from_secs(10);
const NFS_MAX_CHUNK: usize = 65536; // 64 KB por RPC READ

pub struct NfsFileSource {
    target: NfsTarget,
    client: NfsClient,
    file_handle: Vec<u8>,
    file_size: u64,
}

impl NfsFileSource {
    pub fn open(target: &NfsTarget) -> Result<Self, String> {
        let mut client = NfsClient::connect(&target.host, target.port, NFS_TIMEOUT)?;
        let root_handle = client.mount(&target.export_path)?;
        let (file_handle, file_size, is_dir) = client.resolve_path(&root_handle, &target.file_path)?;

        if is_dir {
            return Err(format!("'{}' e um diretorio, nao um arquivo de midia", target.file_path));
        }

        Ok(Self {
            target: target.clone(),
            client,
            file_handle,
            file_size,
        })
    }

    fn reconnect(&mut self) -> Result<(), String> {
        let mut client = NfsClient::connect(&self.target.host, self.target.port, NFS_TIMEOUT)?;
        let root_handle = client.mount(&self.target.export_path)?;
        let (file_handle, file_size, _) = client.resolve_path(&root_handle, &self.target.file_path)?;
        self.client = client;
        self.file_handle = file_handle;
        self.file_size = file_size;
        Ok(())
    }
}

impl RangeSource for NfsFileSource {
    fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        if offset >= self.file_size || buf.is_empty() {
            return Ok(0);
        }

        let max_to_read = (self.file_size - offset).min(buf.len() as u64) as usize;
        let mut total_read = 0;

        while total_read < max_to_read {
            let cur_offset = offset + (total_read as u64);
            let chunk_size = (max_to_read - total_read).min(NFS_MAX_CHUNK) as u32;

            let read_res = self.client.read(&self.file_handle, cur_offset, chunk_size);
            let (data, eof) = match read_res {
                Ok(res) => res,
                Err(err_msg) => {
                    // Tenta reconectar 1 vez em caso de timeout/queda
                    if let Ok(()) = self.reconnect() {
                        self.client
                            .read(&self.file_handle, cur_offset, chunk_size)
                            .map_err(|e| io::Error::new(io::ErrorKind::ConnectionReset, e))?
                    } else {
                        return Err(io::Error::new(io::ErrorKind::ConnectionReset, err_msg));
                    }
                }
            };

            if data.is_empty() {
                break;
            }

            buf[total_read..total_read + data.len()].copy_from_slice(&data);
            total_read += data.len();

            if eof {
                break;
            }
        }

        Ok(total_read)
    }

    fn len(&self) -> Option<u64> {
        Some(self.file_size)
    }
}
