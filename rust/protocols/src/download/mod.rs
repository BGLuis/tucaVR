//! Módulo de Download Offline (Fase 0.4 Seção 4 / T4.1-T4.3).
//!
//! Fornece gerenciamento de downloads com fila concorrente (limite configurável),
//! suporte universal a retomada (resume) através da trait `RangeSource` para
//! todos os protocolos suportados (SMB, SFTP, FTP, NFS, WebDAV e HTTP/HTTPS),
//! rastreamento atômico de progresso/velocidade e auto-pausa durante reprodução remota.

use crate::prefetch::RangeSource;
use std::fs::OpenOptions;
use std::io::{Seek, SeekFrom, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

/// Tamanho do chunk de leitura para download (256 KB).
const DOWNLOAD_CHUNK_SIZE: usize = 256 * 1024;

/// Estado do download, sincronizado com o Kotlin/Room.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum DownloadState {
    Queued = 0,
    Downloading = 1,
    Paused = 2,
    Completed = 3,
    Failed = 4,
    Cancelled = 5,
}

impl DownloadState {
    pub fn from_u32(val: u32) -> Self {
        match val {
            0 => DownloadState::Queued,
            1 => DownloadState::Downloading,
            2 => DownloadState::Paused,
            3 => DownloadState::Completed,
            4 => DownloadState::Failed,
            5 => DownloadState::Cancelled,
            _ => DownloadState::Failed,
        }
    }
}

/// Instantâneo de estatísticas de uma tarefa para polling da UI / JNI.
#[derive(Debug, Clone)]
pub struct DownloadStats {
    pub id: String,
    pub total_bytes: u64,
    pub downloaded_bytes: u64,
    pub speed_bps: u64,
    pub state: DownloadState,
    pub error_message: Option<String>,
}

/// Tarefa individual de download.
pub struct DownloadTask {
    pub id: String,
    pub source_uri: String,
    pub destination_path: PathBuf,
    pub part_path: PathBuf,
    pub total_bytes: AtomicU64,
    pub downloaded_bytes: AtomicU64,
    pub state: AtomicU32,
    pub speed_bps: AtomicU64,
    pub error_message: Mutex<Option<String>>,
    pub cancel_flag: Arc<AtomicBool>,
    pub pause_flag: Arc<AtomicBool>,
}

impl DownloadTask {
    pub fn new(id: String, source_uri: String, destination_path: PathBuf) -> Self {
        let part_path = PathBuf::from(format!("{}.part", destination_path.display()));
        Self {
            id,
            source_uri,
            destination_path,
            part_path,
            total_bytes: AtomicU64::new(0),
            downloaded_bytes: AtomicU64::new(0),
            state: AtomicU32::new(DownloadState::Queued as u32),
            speed_bps: AtomicU64::new(0),
            error_message: Mutex::new(None),
            cancel_flag: Arc::new(AtomicBool::new(false)),
            pause_flag: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn get_state(&self) -> DownloadState {
        DownloadState::from_u32(self.state.load(Ordering::Relaxed))
    }

    pub fn set_state(&self, state: DownloadState) {
        self.state.store(state as u32, Ordering::Relaxed);
    }

    pub fn snapshot(&self) -> DownloadStats {
        let err = self.error_message.lock().ok().and_then(|guard| guard.clone());
        DownloadStats {
            id: self.id.clone(),
            total_bytes: self.total_bytes.load(Ordering::Relaxed),
            downloaded_bytes: self.downloaded_bytes.load(Ordering::Relaxed),
            speed_bps: self.speed_bps.load(Ordering::Relaxed),
            state: self.get_state(),
            error_message: err,
        }
    }
}

/// Tipo de fábrica para abertura de `RangeSource` a partir de uma URI.
pub type SourceOpener = Arc<dyn Fn(&str) -> Result<Box<dyn RangeSource>, String> + Send + Sync>;

/// Abre uma `RangeSource` real com base na URI fornecida para qualquer protocolo suportado.
pub fn default_source_opener(uri: &str) -> Result<Box<dyn RangeSource>, String> {
    if uri.starts_with("smb://") {
        let target = crate::smb::SmbTarget::from_internal(uri)
            .ok_or_else(|| format!("URI SMB inválida: {}", crate::smb::redact(uri)))?;
        let source = crate::smb::SmbFileSource::open(&target)?;
        Ok(Box::new(source))
    } else if uri.starts_with("sftp://") {
        let target = crate::sftp::SftpTarget::from_internal(uri)
            .ok_or_else(|| format!("URI SFTP inválida: {}", crate::sftp::redact(uri)))?;
        let source = crate::sftp::SftpFileSource::open(&target)?;
        Ok(Box::new(source))
    } else if uri.starts_with("ftp://") {
        let target = crate::ftp::FtpTarget::from_internal(uri)
            .ok_or_else(|| format!("URI FTP inválida: {}", crate::ftp::redact(uri)))?;
        let source = crate::ftp::FtpFileSource::open(&target)?;
        Ok(Box::new(source))
    } else if uri.starts_with("nfs://") {
        let target = crate::nfs::NfsTarget::from_internal(uri)
            .ok_or_else(|| format!("URI NFS inválida: {}", crate::nfs::redact(uri)))?;
        let source = crate::nfs::NfsFileSource::open(&target)?;
        Ok(Box::new(source))
    } else if uri.starts_with("webdav://") || uri.starts_with("webdavs://") {
        let target = crate::webdav::WebdavTarget::from_internal(uri)
            .ok_or_else(|| format!("URI WebDAV inválida: {}", crate::webdav::redact(uri)))?;
        let source = crate::webdav::WebdavFileSource::open(&target)?;
        Ok(Box::new(source))
    } else if uri.starts_with("http://") || uri.starts_with("https://") {
        let source = crate::http::HttpsRangeSource::new(uri)?;
        Ok(Box::new(source))
    } else {
        Err(format!("Esquema de protocolo não suportado para download: {uri}"))
    }
}

/// Gerenciador de downloads com fila concorrente e controle de ciclo de vida.
pub struct DownloadManager {
    tasks: Mutex<Vec<Arc<DownloadTask>>>,
    max_concurrent: usize,
    source_opener: SourceOpener,
    playback_active: Arc<AtomicBool>,
}

impl DownloadManager {
    pub fn new(max_concurrent: usize) -> Self {
        Self::with_opener(max_concurrent, Arc::new(default_source_opener))
    }

    pub fn with_opener(max_concurrent: usize, opener: SourceOpener) -> Self {
        Self {
            tasks: Mutex::new(Vec::new()),
            max_concurrent: max_concurrent.max(1),
            source_opener: opener,
            playback_active: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Define se há reprodução ativa na rede para auto-pausa (T4.3).
    pub fn set_playback_active(&self, active: bool) {
        self.playback_active.store(active, Ordering::Relaxed);
    }

    pub fn is_playback_active(&self) -> bool {
        self.playback_active.load(Ordering::Relaxed)
    }

    /// Enfileira uma nova tarefa de download.
    pub fn enqueue(&self, id: &str, source_uri: &str, destination_path: &str) -> Result<(), String> {
        let mut tasks = self.tasks.lock().map_err(|_| "Mutex envenenado".to_string())?;

        // Se a tarefa já existe, apenas reativa se estiver pausada/falha/cancelada
        if let Some(existing) = tasks.iter().find(|t| t.id == id) {
            let state = existing.get_state();
            if state == DownloadState::Paused || state == DownloadState::Failed || state == DownloadState::Cancelled {
                existing.pause_flag.store(false, Ordering::Relaxed);
                existing.cancel_flag.store(false, Ordering::Relaxed);
                existing.set_state(DownloadState::Queued);
                drop(tasks);
                self.schedule();
                return Ok(());
            }
            return Ok(());
        }

        let task = Arc::new(DownloadTask::new(
            id.to_string(),
            source_uri.to_string(),
            PathBuf::from(destination_path),
        ));
        tasks.push(task);
        drop(tasks);

        self.schedule();
        Ok(())
    }

    /// Pausa uma tarefa em andamento ou enfileirada.
    pub fn pause(&self, id: &str) -> bool {
        let Ok(tasks) = self.tasks.lock() else { return false };
        let Some(task) = tasks.iter().find(|t| t.id == id) else { return false };

        task.pause_flag.store(true, Ordering::Relaxed);
        let state = task.get_state();
        if state == DownloadState::Queued {
            task.set_state(DownloadState::Paused);
        }
        true
    }

    /// Retoma uma tarefa pausada.
    pub fn resume(&self, id: &str) -> bool {
        let Ok(tasks) = self.tasks.lock() else { return false };
        let Some(task) = tasks.iter().find(|t| t.id == id) else { return false };

        let state = task.get_state();
        if state == DownloadState::Paused || state == DownloadState::Failed {
            task.pause_flag.store(false, Ordering::Relaxed);
            task.cancel_flag.store(false, Ordering::Relaxed);
            task.set_state(DownloadState::Queued);
            drop(tasks);
            self.schedule();
            return true;
        }
        false
    }

    /// Cancela uma tarefa e remove o arquivo `.part`.
    pub fn cancel(&self, id: &str) -> bool {
        let Ok(tasks) = self.tasks.lock() else { return false };
        let Some(task) = tasks.iter().find(|t| t.id == id) else { return false };

        task.cancel_flag.store(true, Ordering::Relaxed);
        task.set_state(DownloadState::Cancelled);

        // Tenta remover o arquivo parcial se existir
        let _ = std::fs::remove_file(&task.part_path);

        drop(tasks);
        self.schedule();
        true
    }

    /// Obtém estatísticas de uma tarefa específica.
    pub fn get_stats(&self, id: &str) -> Option<DownloadStats> {
        let tasks = self.tasks.lock().ok()?;
        tasks.iter().find(|t| t.id == id).map(|t| t.snapshot())
    }

    /// Obtém estatísticas de todas as tarefas.
    pub fn get_all_stats(&self) -> Vec<DownloadStats> {
        let Ok(tasks) = self.tasks.lock() else { return Vec::new() };
        tasks.iter().map(|t| t.snapshot()).collect()
    }

    /// Agenda e dispara tarefas da fila respeitando a concorrência máxima.
    pub fn schedule(&self) {
        let Ok(tasks) = self.tasks.lock() else { return };

        let active_count = tasks
            .iter()
            .filter(|t| t.get_state() == DownloadState::Downloading)
            .count();

        if active_count >= self.max_concurrent {
            return;
        }

        let available_slots = self.max_concurrent - active_count;
        let queued_tasks: Vec<Arc<DownloadTask>> = tasks
            .iter()
            .filter(|t| t.get_state() == DownloadState::Queued)
            .take(available_slots)
            .cloned()
            .collect();

        drop(tasks);

        for task in queued_tasks {
            self.spawn_worker(task);
        }
    }

    fn spawn_worker(&self, task: Arc<DownloadTask>) {
        task.set_state(DownloadState::Downloading);
        task.pause_flag.store(false, Ordering::Relaxed);
        task.cancel_flag.store(false, Ordering::Relaxed);

        let opener = self.source_opener.clone();
        let playback_active = self.playback_active.clone();

        let task_clone = task.clone();
        thread::spawn(move || {
            let result = execute_download(&task_clone, &opener, &playback_active);
            match result {
                Ok(()) => {
                    if task_clone.cancel_flag.load(Ordering::Relaxed) {
                        task_clone.set_state(DownloadState::Cancelled);
                        let _ = std::fs::remove_file(&task_clone.part_path);
                    } else if task_clone.pause_flag.load(Ordering::Relaxed) {
                        task_clone.set_state(DownloadState::Paused);
                    } else {
                        task_clone.set_state(DownloadState::Completed);
                    }
                }
                Err(err) => {
                    if task_clone.cancel_flag.load(Ordering::Relaxed) {
                        task_clone.set_state(DownloadState::Cancelled);
                        let _ = std::fs::remove_file(&task_clone.part_path);
                    } else if task_clone.pause_flag.load(Ordering::Relaxed) {
                        task_clone.set_state(DownloadState::Paused);
                    } else {
                        log::error!("Erro no download [{}] ({}): {err}", task_clone.id, task_clone.destination_path.display());
                        if let Ok(mut guard) = task_clone.error_message.lock() {
                            *guard = Some(err);
                        }
                        task_clone.set_state(DownloadState::Failed);
                    }
                }
            }
            task_clone.speed_bps.store(0, Ordering::Relaxed);
        });
    }
}

/// Loop de download com resume e escrita em arquivo `.part`.
fn execute_download(
    task: &DownloadTask,
    opener: &SourceOpener,
    playback_active: &AtomicBool,
) -> Result<(), String> {
    // Garante que o diretório de destino existe
    if let Some(parent) = task.destination_path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("Falha ao criar diretório: {e}"))?;
    }

    // Verifica bytes existentes no arquivo parcial
    let existing_bytes = if task.part_path.exists() {
        std::fs::metadata(&task.part_path).map(|m| m.len()).unwrap_or(0)
    } else {
        0
    };
    task.downloaded_bytes.store(existing_bytes, Ordering::Relaxed);

    // Abre a fonte do protocolo remoto
    let mut source = opener(&task.source_uri)?;
    let total_bytes = source.len().unwrap_or(0);
    task.total_bytes.store(total_bytes, Ordering::Relaxed);

    // Se já completou anteriormente, basta renomear
    if total_bytes > 0 && existing_bytes >= total_bytes {
        let _ = std::fs::rename(&task.part_path, &task.destination_path);
        return Ok(());
    }

    // Abre arquivo `.part` para escrita com append / seek
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(&task.part_path)
        .map_err(|e| format!("Falha ao abrir arquivo temporário: {e}"))?;

    file.seek(SeekFrom::Start(existing_bytes))
        .map_err(|e| format!("Falha no seek do arquivo: {e}"))?;

    let mut current_offset = existing_bytes;
    let mut buf = vec![0u8; DOWNLOAD_CHUNK_SIZE];

    let mut last_speed_check = Instant::now();
    let mut bytes_since_speed_check = 0u64;

    while total_bytes == 0 || current_offset < total_bytes {
        // Checa flags de cancelamento ou pausa
        if task.cancel_flag.load(Ordering::Relaxed) {
            return Ok(());
        }
        if task.pause_flag.load(Ordering::Relaxed) {
            let _ = file.flush();
            return Ok(());
        }

        // Auto-pausa durante reprodução de rede (T4.3)
        if playback_active.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_millis(250));
            continue;
        }

        let want = if total_bytes > 0 {
            ((total_bytes - current_offset).min(DOWNLOAD_CHUNK_SIZE as u64)) as usize
        } else {
            DOWNLOAD_CHUNK_SIZE
        };

        let n = source
            .read_range(current_offset, &mut buf[..want])
            .map_err(|e| format!("Erro de leitura da rede no offset {current_offset}: {e}"))?;

        if n == 0 {
            // EOF alcançado
            break;
        }

        file.write_all(&buf[..n])
            .map_err(|e| format!("Erro de gravação em disco: {e}"))?;

        current_offset += n as u64;
        bytes_since_speed_check += n as u64;
        task.downloaded_bytes.store(current_offset, Ordering::Relaxed);

        // Atualiza cálculo de velocidade a cada ~500ms
        let elapsed = last_speed_check.elapsed();
        if elapsed >= Duration::from_millis(500) {
            let speed = (bytes_since_speed_check as f64 / elapsed.as_secs_f64()) as u64;
            task.speed_bps.store(speed, Ordering::Relaxed);
            last_speed_check = Instant::now();
            bytes_since_speed_check = 0;
        }
    }

    file.flush().map_err(|e| format!("Falha ao descarregar buffer: {e}"))?;
    drop(file);

    // Valida se o download atingiu o tamanho esperado (quando conhecido)
    if total_bytes > 0 && current_offset < total_bytes {
        return Err(format!(
            "Download prematuramente interrompido: recebidos {} de {} bytes",
            current_offset, total_bytes
        ));
    }

    // Renomeia o arquivo temporário `.part` para o nome de destino final
    std::fs::rename(&task.part_path, &task.destination_path)
        .map_err(|e| format!("Falha ao renomear arquivo temporário para final: {e}"))?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io;
    use std::sync::atomic::AtomicUsize;

    /// Mock de RangeSource baseado em bytes na memória para testes unitários isolados.
    struct MockRangeSource {
        data: Vec<u8>,
        reads_count: Arc<AtomicUsize>,
    }

    impl MockRangeSource {
        fn new(data: Vec<u8>, reads_count: Arc<AtomicUsize>) -> Self {
            Self { data, reads_count }
        }
    }

    impl RangeSource for MockRangeSource {
        fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
            self.reads_count.fetch_add(1, Ordering::Relaxed);
            if offset >= self.data.len() as u64 {
                return Ok(0);
            }
            let avail = (self.data.len() as u64 - offset) as usize;
            let to_read = buf.len().min(avail);
            let start = offset as usize;
            buf[..to_read].copy_from_slice(&self.data[start..start + to_read]);
            Ok(to_read)
        }

        fn len(&self) -> Option<u64> {
            Some(self.data.len() as u64)
        }
    }

    #[test]
    fn test_download_state_conversion() {
        assert_eq!(DownloadState::from_u32(0), DownloadState::Queued);
        assert_eq!(DownloadState::from_u32(1), DownloadState::Downloading);
        assert_eq!(DownloadState::from_u32(2), DownloadState::Paused);
        assert_eq!(DownloadState::from_u32(3), DownloadState::Completed);
        assert_eq!(DownloadState::from_u32(4), DownloadState::Failed);
        assert_eq!(DownloadState::from_u32(5), DownloadState::Cancelled);
        assert_eq!(DownloadState::from_u32(99), DownloadState::Failed);
    }

    #[test]
    fn test_download_completes_and_renames_part_file() {
        let temp_dir = std::env::temp_dir().join(format!("tucavr_test_dl_{}", std::process::id()));
        let _ = std::fs::create_dir_all(&temp_dir);
        let dest_file = temp_dir.join("test_video.mp4");
        let part_file = temp_dir.join("test_video.mp4.part");

        let test_data = vec![0x42u8; 100_000]; // 100 KB
        let reads = Arc::new(AtomicUsize::new(0));
        let data_clone = test_data.clone();
        let reads_clone = reads.clone();

        let opener: SourceOpener = Arc::new(move |_uri| {
            Ok(Box::new(MockRangeSource::new(data_clone.clone(), reads_clone.clone())))
        });

        let manager = DownloadManager::with_opener(2, opener);
        manager.enqueue("dl_1", "mock://video", dest_file.to_str().unwrap()).unwrap();

        // Aguarda conclusão (máximo 2 segundos)
        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(2) {
            let stats = manager.get_stats("dl_1").unwrap();
            if stats.state == DownloadState::Completed {
                break;
            }
            thread::sleep(Duration::from_millis(10));
        }

        let stats = manager.get_stats("dl_1").unwrap();
        assert_eq!(stats.state, DownloadState::Completed);
        assert_eq!(stats.total_bytes, 100_000);
        assert_eq!(stats.downloaded_bytes, 100_000);
        assert!(dest_file.exists());
        assert!(!part_file.exists());

        let read_back = std::fs::read(&dest_file).unwrap();
        assert_eq!(read_back, test_data);

        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[test]
    fn test_download_resume_from_part_file() {
        let temp_dir = std::env::temp_dir().join(format!("tucavr_test_resume_{}", std::process::id()));
        let _ = std::fs::create_dir_all(&temp_dir);
        let dest_file = temp_dir.join("resumed_video.mp4");
        let part_file = temp_dir.join("resumed_video.mp4.part");

        // Cria arquivo parcial já com 40 KB
        let test_data: Vec<u8> = (0..100_000).map(|i| (i % 256) as u8).collect();
        std::fs::write(&part_file, &test_data[..40_000]).unwrap();

        let reads = Arc::new(AtomicUsize::new(0));
        let data_clone = test_data.clone();
        let reads_clone = reads.clone();

        let opener: SourceOpener = Arc::new(move |_uri| {
            Ok(Box::new(MockRangeSource::new(data_clone.clone(), reads_clone.clone())))
        });

        let manager = DownloadManager::with_opener(2, opener);
        manager.enqueue("dl_resume", "mock://video", dest_file.to_str().unwrap()).unwrap();

        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(2) {
            let stats = manager.get_stats("dl_resume").unwrap();
            if stats.state == DownloadState::Completed {
                break;
            }
            thread::sleep(Duration::from_millis(10));
        }

        let stats = manager.get_stats("dl_resume").unwrap();
        assert_eq!(stats.state, DownloadState::Completed);
        assert_eq!(stats.downloaded_bytes, 100_000);
        assert!(dest_file.exists());

        let read_back = std::fs::read(&dest_file).unwrap();
        assert_eq!(read_back, test_data);

        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[test]
    fn test_concurrency_limit_respects_max_active() {
        let temp_dir = std::env::temp_dir().join(format!("tucavr_test_conc_{}", std::process::id()));
        let _ = std::fs::create_dir_all(&temp_dir);

        let test_data = vec![0x99u8; 1_000];
        let reads = Arc::new(AtomicUsize::new(0));
        let data_clone = test_data.clone();
        let reads_clone = reads.clone();

        let opener: SourceOpener = Arc::new(move |_uri| {
            Ok(Box::new(MockRangeSource::new(data_clone.clone(), reads_clone.clone())))
        });

        let manager = DownloadManager::with_opener(2, opener);

        for i in 1..=5 {
            let path = temp_dir.join(format!("file_{i}.mp4"));
            manager.enqueue(&format!("id_{i}"), "mock://uri", path.to_str().unwrap()).unwrap();
        }

        // Verifica que no máximo 2 estão em Downloading simultaneamente
        let all = manager.get_all_stats();
        let active = all.iter().filter(|s| s.state == DownloadState::Downloading).count();
        assert!(active <= 2, "Concorrência ativa ({active}) excedeu o limite máximo de 2");

        let _ = std::fs::remove_dir_all(&temp_dir);
    }

    #[test]
    fn test_pause_and_cancel_flags() {
        let temp_dir = std::env::temp_dir().join(format!("tucavr_test_ctrl_{}", std::process::id()));
        let _ = std::fs::create_dir_all(&temp_dir);
        let path1 = temp_dir.join("pause.mp4");
        let path2 = temp_dir.join("cancel.mp4");

        let opener: SourceOpener = Arc::new(|_uri| {
            Ok(Box::new(MockRangeSource::new(vec![0u8; 100], Arc::new(AtomicUsize::new(0)))))
        });

        let manager = DownloadManager::with_opener(1, opener);
        manager.enqueue("t_pause", "mock://uri", path1.to_str().unwrap()).unwrap();
        assert!(manager.pause("t_pause"));

        manager.enqueue("t_cancel", "mock://uri", path2.to_str().unwrap()).unwrap();
        assert!(manager.cancel("t_cancel"));
        let stats_cancel = manager.get_stats("t_cancel").unwrap();
        assert_eq!(stats_cancel.state, DownloadState::Cancelled);

        let _ = std::fs::remove_dir_all(&temp_dir);
    }
}
