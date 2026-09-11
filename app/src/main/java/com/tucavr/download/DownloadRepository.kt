package com.tucavr.download

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.tucavr.history.AppDatabase
import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class InsufficientSpaceException(message: String) : Exception(message)

data class DownloadStatsSnapshot(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val state: String
)

/**
 * Repositório e coordenador de downloads offline (Fase 0.4 Seção 4).
 *
 * Gerencia a persistência no Room, sincronização com a bridge Rust/C++,
 * verificação preventiva de armazenamento e disparo do [DownloadService].
 */
class DownloadRepository(
    private val context: Context? = null,
    private val dao: DownloadDao,
    private val bridge: NativeDownloadBridge = DownloadBridge,
    private val diskSpaceManager: DiskSpaceManager = DiskSpaceManager(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    constructor(context: Context) : this(
        context = context,
        dao = AppDatabase.getInstance(context).downloadDao(),
        bridge = DownloadBridge,
        diskSpaceManager = DiskSpaceManager()
    )
    companion object {
        fun getDefaultDownloadDir(): File {
            return try {
                val downloadsPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(downloadsPublic, "tucaVR")
            } catch (_: Throwable) {
                File(System.getProperty("java.io.tmpdir", "/tmp"), "tucaVR_downloads")
            }
        }
    }

    suspend fun enqueue(
        source: PlaybackSource,
        displayName: String,
        sourceSize: Long = 0L,
        customDestDir: File? = null
    ): Result<Download> = withContext(Dispatchers.IO) {
        val destDir = customDestDir ?: getDefaultDownloadDir()
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        // T4.6: Verificação de espaço livre com buffer de segurança
        if (!diskSpaceManager.hasSufficientSpace(destDir, sourceSize)) {
            return@withContext Result.failure(
                InsufficientSpaceException("Espaço insuficiente em disco para baixar $displayName")
            )
        }

        val internalUri = source.toDownloadInternalUri(context)
        val id = UUID.randomUUID().toString()
        val destFile = File(destDir, sanitizeFilename(displayName))

        val download = Download(
            id = id,
            sourceUri = internalUri,
            sourceType = source.toSourceTypeString(),
            destinationPath = destFile.absolutePath,
            displayName = displayName,
            totalBytes = sourceSize,
            downloadedBytes = 0L,
            state = DownloadStatus.QUEUED,
            createdAt = System.currentTimeMillis()
        )

        dao.upsert(download)

        // Enfileira no motor nativo
        bridge.nativeEnqueue(id, internalUri, destFile.absolutePath)

        // Acorda o ForegroundService para manter o download vivo em background
        context?.let { ctx ->
            try {
                val serviceIntent = Intent(ctx, DownloadService::class.java)
                ctx.startForegroundService(serviceIntent)
            } catch (_: Exception) {
                // Pode falhar em testes unitários puros na JVM sem framework Android
            }
        }

        Result.success(download)
    }

    suspend fun pause(id: String) = withContext(Dispatchers.IO) {
        bridge.nativePause(id)
        dao.updateProgress(id, getDownloadedBytesOrSaved(id), DownloadStatus.PAUSED)
    }

    suspend fun resume(id: String) = withContext(Dispatchers.IO) {
        val download = dao.findById(id) ?: return@withContext
        val ret = bridge.nativeResume(id)
        if (ret != 0) {
            // Se o processo nativo não tinha mais a tarefa em memória, re-enfileira
            bridge.nativeEnqueue(id, download.sourceUri, download.destinationPath)
        }
        dao.updateProgress(id, download.downloadedBytes, DownloadStatus.QUEUED)

        context?.let { ctx ->
            try {
                val serviceIntent = Intent(ctx, DownloadService::class.java)
                ctx.startForegroundService(serviceIntent)
            } catch (_: Exception) {}
        }
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        bridge.nativeCancel(id)
        val download = dao.findById(id)
        if (download != null) {
            val partFile = File("${download.destinationPath}.part")
            if (partFile.exists()) {
                partFile.delete()
            }
        }
        dao.updateProgress(id, 0L, DownloadStatus.CANCELLED)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val download = dao.findById(id)
        if (download != null) {
            cancel(id)
            val file = File(download.destinationPath)
            if (file.exists()) {
                file.delete()
            }
        }
        dao.deleteById(id)
    }

    suspend fun listAll(): List<Download> = withContext(Dispatchers.IO) {
        dao.listAll()
    }

    suspend fun listActive(): List<Download> = withContext(Dispatchers.IO) {
        dao.listActive()
    }

    suspend fun clearCompleted() = withContext(Dispatchers.IO) {
        dao.clearCompleted()
    }

    fun getStats(id: String): DownloadStatsSnapshot? {
        val stats = bridge.nativeGetStats(id) ?: return null
        if (stats.size < 4) return null
        val downloaded = stats[0]
        val total = stats[1]
        val speed = stats[2]
        val stateNum = stats[3]

        val stateStr = when (stateNum) {
            0L -> DownloadStatus.QUEUED
            1L -> DownloadStatus.DOWNLOADING
            2L -> DownloadStatus.PAUSED
            3L -> DownloadStatus.COMPLETED
            4L -> DownloadStatus.FAILED
            5L -> DownloadStatus.CANCELLED
            else -> DownloadStatus.FAILED
        }

        return DownloadStatsSnapshot(downloaded, total, speed, stateStr)
    }

    /**
     * Sincroniza o progresso em tempo real das tarefas ativas com o banco Room.
     * Retorna `true` enquanto houver tarefas em andamento.
     */
    suspend fun syncActiveDownloads(): Boolean = withContext(Dispatchers.IO) {
        val active = dao.listActive()
        if (active.isEmpty()) return@withContext false

        var hasRunning = false
        for (task in active) {
            val live = getStats(task.id)
            if (live != null) {
                if (live.state == DownloadStatus.COMPLETED) {
                    dao.updateCompleted(task.id, System.currentTimeMillis())
                } else if (live.state == DownloadStatus.FAILED) {
                    dao.updateFailed(task.id, "Falha durante o download")
                } else {
                    dao.updateProgress(task.id, live.downloadedBytes, live.state)
                    if (live.state == DownloadStatus.DOWNLOADING || live.state == DownloadStatus.QUEUED) {
                        hasRunning = true
                    }
                }
            } else {
                // Sem stats da lib nativa (ex.: app reiniciou)
                if (task.state == DownloadStatus.DOWNLOADING) {
                    dao.updateProgress(task.id, task.downloadedBytes, DownloadStatus.PAUSED)
                }
            }
        }
        hasRunning
    }

    private suspend fun getDownloadedBytesOrSaved(id: String): Long {
        return getStats(id)?.downloadedBytes ?: dao.findById(id)?.downloadedBytes ?: 0L
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[/\\\\?%*:|\"<>]"), "_")
    }
}

/**
 * Converte um [PlaybackSource] na URI interna utilizada pelos protocolos em Rust.
 */
fun PlaybackSource.toDownloadInternalUri(context: Context? = null): String {
    val sep = '\u0000'
    return when (this) {
        is PlaybackSource.Http -> url
        is PlaybackSource.Smb -> {
            "smb://${server.host}:${server.port}$sep${server.share}$sep${path.removePrefix("/")}$sep${server.username}$sep${server.password}$sep${server.domain}"
        }
        is PlaybackSource.Ftp -> {
            "ftp://${server.host}:${server.port}$sep${path.removePrefix("/")}$sep${server.username}$sep${server.password}"
        }
        is PlaybackSource.Sftp -> {
            "sftp://${server.host}:${server.port}$sep${path.removePrefix("/")}$sep${server.username}$sep${server.password}$sep${server.privateKey ?: ""}"
        }
        is PlaybackSource.Nfs -> {
            "nfs://${server.host}:${server.port}$sep${server.path}$sep${path.removePrefix("/")}${sep}3"
        }
        is PlaybackSource.Webdav -> {
            val pass = if (context != null) {
                try {
                    com.tucavr.network.ServerCredentialStore(context).getPassword(server.id)
                } catch (_: Exception) {
                    ""
                }
            } else ""
            var useHttps = "0"
            var acceptInvalidCerts = "0"
            if (!server.extraJson.isNullOrEmpty()) {
                val raw = server.extraJson
                if (raw.contains("\"useHttps\":true") || raw.contains("\"useHttps\": true")) useHttps = "1"
                if (raw.contains("\"acceptInvalidCerts\":true") || raw.contains("\"acceptInvalidCerts\": true")) acceptInvalidCerts = "1"
                try {
                    val json = org.json.JSONObject(raw)
                    if (json.optBoolean("useHttps", false)) useHttps = "1"
                    if (json.optBoolean("acceptInvalidCerts", false)) acceptInvalidCerts = "1"
                } catch (_: Throwable) {}
            }
            "webdav://${server.host}:${server.port}$sep${server.path}$sep${path.removePrefix("/")}$sep${server.username}$sep$pass$sep$useHttps$sep$acceptInvalidCerts"
        }
        is PlaybackSource.Dlna -> url
        is PlaybackSource.LocalFile -> path
    }
}

fun PlaybackSource.toSourceTypeString(): String {
    return when (this) {
        is PlaybackSource.Http -> "HTTP"
        is PlaybackSource.Smb -> "SMB"
        is PlaybackSource.Ftp -> "FTP"
        is PlaybackSource.Sftp -> "SFTP"
        is PlaybackSource.Nfs -> "NFS"
        is PlaybackSource.Webdav -> "WEBDAV"
        is PlaybackSource.Dlna -> "DLNA"
        is PlaybackSource.LocalFile -> "LOCAL"
    }
}
