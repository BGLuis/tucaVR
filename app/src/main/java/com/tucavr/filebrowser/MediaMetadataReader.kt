package com.tucavr.filebrowser

import android.content.Context
import com.tucavr.VRActivity
import com.tucavr.history.AppDatabase
import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TrackKind { VIDEO, AUDIO, SUBTITLE }

data class TrackInfo(
    val kind: TrackKind,
    val ordinal: Int,
    val codec: String,
    val language: String,
    val title: String,
    val width: Int,
    val height: Int,
    val fpsMilli: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitRate: Long,
    val isDefault: Boolean,
)

data class MediaMetadata(
    val container: String,
    val containerLong: String,
    val durationMs: Long,
    val bitRate: Long,
    val format3dIndex: Int = 0,
    val detectionConfidence: Int = 4,
    val tags: List<Pair<String, String>>,
    val tracks: List<TrackInfo>,
) {
    val videoTracks get() = tracks.filter { it.kind == TrackKind.VIDEO }
    val audioTracks get() = tracks.filter { it.kind == TrackKind.AUDIO }
    val subtitleTracks get() = tracks.filter { it.kind == TrackKind.SUBTITLE }
}

internal fun PlaybackSource.fileSizeBytes(): Long =
    when (this) {
        is PlaybackSource.LocalFile ->
            if (sizeBytes > 0L) {
                sizeBytes
            } else {
                runCatching { java.io.File(path).length() }.getOrDefault(0L)
            }
        is PlaybackSource.Smb -> sizeBytes
        is PlaybackSource.Ftp -> sizeBytes
        is PlaybackSource.Sftp -> sizeBytes
        is PlaybackSource.Nfs -> sizeBytes
        is PlaybackSource.Dlna -> sizeBytes
        is PlaybackSource.Webdav -> sizeBytes
        is PlaybackSource.Http -> 0L
    }

// Le metadados de midia (T13.1) via Rust (rust/media_logic/src/metadata_wire.rs
// define a gramatica) -- caminho unico pra local e rede, substitui o antigo
// VideoMetadataReader (MediaMetadataRetriever, so local, so 3 campos).
object MediaMetadataReader {
    // Cache-first: se o Room tiver metadados completos (tracksJson preenchido)
    // e o tamanho do arquivo bater, devolve o cache imediatamente sem probe Rust.
    // Em cache miss, busca do Rust e grava o registro completo via [cacheSummary].
    suspend fun read(
        activity: VRActivity,
        source: PlaybackSource,
    ): MediaMetadata? =
        withContext(Dispatchers.IO) {
            val key = runCatching { CacheKeys.forSource(source) }.getOrNull()
            if (key != null) {
                val cached = AppDatabase.getInstance(activity).mediaMetadataCacheDao().find(key)
                val sourceSize = source.fileSizeBytes()
                val sizeMatches = sourceSize == 0L || cached?.fileSizeBytes == 0L || cached?.fileSizeBytes == sourceSize
                if (cached != null && cached.tracksJson.isNotEmpty() && sizeMatches) {
                    return@withContext cached.toMediaMetadata()
                }
            }

            val wire =
                when (source) {
                    is PlaybackSource.LocalFile -> activity.nativeReadMediaMetadata(source.path)
                    is PlaybackSource.Http -> activity.nativeReadMediaMetadata(source.url)
                    is PlaybackSource.Dlna -> activity.nativeReadMediaMetadata(source.url)
                    is PlaybackSource.Smb ->
                        activity.nativeSmbReadMetadata(
                            source.server.host,
                            source.server.port,
                            source.server.username,
                            source.server.password,
                            source.server.domain,
                            source.server.share,
                            source.path,
                        )
                    is PlaybackSource.Ftp ->
                        activity.nativeFtpReadMetadata(
                            source.server.host,
                            source.server.port,
                            source.server.username,
                            source.server.password,
                            source.path,
                        )
                    is PlaybackSource.Sftp ->
                        activity.nativeSftpReadMetadata(
                            source.server.host,
                            source.server.port,
                            source.server.username,
                            source.server.password,
                            source.server.privateKey ?: "",
                            source.server.hostKeyFingerprint ?: "",
                            source.path,
                        )
                    is PlaybackSource.Nfs ->
                        activity.nativeNfsReadMetadata(
                            source.server.host,
                            source.server.port,
                            source.server.path,
                            source.path,
                            3,
                        )
                    is PlaybackSource.Webdav -> {
                        val password = com.tucavr.network.ServerCredentialStore(activity).getPassword(source.server.id)
                        var useHttps = false
                        var acceptInvalidCerts = false
                        if (!source.server.extraJson.isNullOrEmpty()) {
                            try {
                                val json = org.json.JSONObject(source.server.extraJson)
                                useHttps = json.optBoolean("useHttps", false)
                                acceptInvalidCerts = json.optBoolean("acceptInvalidCerts", false)
                            } catch (_: Exception) {
                            }
                        }
                        activity.nativeWebdavReadMetadata(
                            source.server.host,
                            source.server.port,
                            source.server.path,
                            source.path,
                            source.server.username,
                            password,
                            useHttps,
                            acceptInvalidCerts,
                        )
                    }
                }
            if (wire.isEmpty()) return@withContext null
            val metadata = parse(wire) ?: return@withContext null
            cacheSummary(activity, source, metadata)
            metadata
        }

    /**
     * Leitura SOMENTE do cache Room (nunca chama o Rust) -- usada pela listagem
     * (`FileAdapter`) pra exibir um badge de resolução/duração sem custo. Cache miss
     * (arquivo ainda não aberto em detalhe/player) simplesmente não mostra nada; o
     * cálculo real só acontece via [read], que popula o cache pra próxima renderização.
     */
    suspend fun readCachedSummary(
        context: Context,
        source: PlaybackSource,
    ): MediaMetadataCacheEntry? =
        withContext(Dispatchers.IO) {
            val key = runCatching { CacheKeys.forSource(source) }.getOrNull() ?: return@withContext null
            AppDatabase.getInstance(context).mediaMetadataCacheDao().find(key)
        }

    /**
     * Leitura SOMENTE do cache Room de [MediaMetadata] completo (nunca chama o Rust).
     * Devolve nulo se não houver entrada no cache ou se as faixas não estiverem gravadas.
     */
    suspend fun readCached(
        context: Context,
        source: PlaybackSource,
    ): MediaMetadata? =
        withContext(Dispatchers.IO) {
            val key = runCatching { CacheKeys.forSource(source) }.getOrNull() ?: return@withContext null
            val entry = AppDatabase.getInstance(context).mediaMetadataCacheDao().find(key) ?: return@withContext null
            if (entry.tracksJson.isNotEmpty()) {
                entry.toMediaMetadata()
            } else {
                null
            }
        }

    internal suspend fun cacheSummary(
        context: Context,
        source: PlaybackSource,
        metadata: MediaMetadata,
    ) {
        val key = runCatching { CacheKeys.forSource(source) }.getOrNull() ?: return
        val videoTrack = metadata.videoTracks.firstOrNull()
        val entry =
            MediaMetadataCacheEntry(
                mediaKey = key,
                container = metadata.container,
                containerLong = metadata.containerLong,
                durationMs = metadata.durationMs,
                bitRate = metadata.bitRate,
                format3dIndex = metadata.format3dIndex,
                detectionConfidence = metadata.detectionConfidence,
                videoWidth = videoTrack?.width ?: 0,
                videoHeight = videoTrack?.height ?: 0,
                videoCodec = videoTrack?.codec ?: "",
                fetchedAt = System.currentTimeMillis(),
                tracksJson = serializeTracks(metadata.tracks),
                tagsJson = serializeTags(metadata.tags),
                fileSizeBytes = source.fileSizeBytes(),
            )
        runCatching { AppDatabase.getInstance(context).mediaMetadataCacheDao().upsert(entry) }
    }

    // Pura (sem Context/Activity) -- testavel na JVM. Ignora linhas
    // desconhecidas/malformadas em vez de falhar tudo, pra a wire poder
    // ganhar registros novos no futuro sem quebrar parsers antigos.
    internal fun parse(wire: String): MediaMetadata? {
        if (wire.startsWith("ERROR:")) return null

        var container = ""
        var containerLong = ""
        var durationMs = 0L
        var bitRate = 0L
        var format3dIndex = 0
        var detectionConfidence = 4
        val tags = mutableListOf<Pair<String, String>>()
        val tracks = mutableListOf<TrackInfo>()

        wire.split("\n").forEach { line ->
            val parts = line.split("\t")
            when (parts.getOrNull(0)) {
                "F" -> {
                    container = parts.getOrNull(1) ?: ""
                    containerLong = parts.getOrNull(2) ?: ""
                    durationMs = ((parts.getOrNull(3)?.toLongOrNull() ?: 0L) / 1000L)
                    bitRate = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    format3dIndex = parts.getOrNull(5)?.toIntOrNull() ?: 0
                    detectionConfidence = parts.getOrNull(6)?.toIntOrNull() ?: 4
                }
                "M" -> {
                    val key = parts.getOrNull(1) ?: return@forEach
                    val value = parts.getOrNull(2) ?: return@forEach
                    tags.add(key to value)
                }
                "T" -> {
                    val kind =
                        when (parts.getOrNull(1)) {
                            "video" -> TrackKind.VIDEO
                            "audio" -> TrackKind.AUDIO
                            "subtitle" -> TrackKind.SUBTITLE
                            else -> return@forEach
                        }
                    tracks.add(
                        TrackInfo(
                            kind = kind,
                            ordinal = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                            codec = parts.getOrNull(3) ?: "",
                            language = parts.getOrNull(4) ?: "",
                            title = parts.getOrNull(5) ?: "",
                            width = parts.getOrNull(6)?.toIntOrNull() ?: 0,
                            height = parts.getOrNull(7)?.toIntOrNull() ?: 0,
                            fpsMilli = parts.getOrNull(8)?.toIntOrNull() ?: 0,
                            channels = parts.getOrNull(9)?.toIntOrNull() ?: 0,
                            sampleRate = parts.getOrNull(10)?.toIntOrNull() ?: 0,
                            bitRate = parts.getOrNull(11)?.toLongOrNull() ?: 0L,
                            isDefault = parts.getOrNull(12) == "1",
                        ),
                    )
                }
            }
        }

        if (container.isEmpty() && tracks.isEmpty()) return null
        return MediaMetadata(container, containerLong, durationMs, bitRate, format3dIndex, detectionConfidence, tags, tracks)
    }
}
