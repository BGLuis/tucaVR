package com.vrplayer.filebrowser

import com.vrplayer.VRActivity
import com.vrplayer.navigation.PlaybackSource
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
    val isDefault: Boolean
)

data class MediaMetadata(
    val container: String,
    val containerLong: String,
    val durationMs: Long,
    val bitRate: Long,
    val format3dIndex: Int = 0,
    val detectionConfidence: Int = 3,
    val tags: List<Pair<String, String>>,
    val tracks: List<TrackInfo>
) {
    val videoTracks get() = tracks.filter { it.kind == TrackKind.VIDEO }
    val audioTracks get() = tracks.filter { it.kind == TrackKind.AUDIO }
    val subtitleTracks get() = tracks.filter { it.kind == TrackKind.SUBTITLE }
}

// Le metadados de midia (T13.1) via Rust (rust/media_logic/src/metadata_wire.rs
// define a gramatica) -- caminho unico pra local e rede, substitui o antigo
// VideoMetadataReader (MediaMetadataRetriever, so local, so 3 campos).
object MediaMetadataReader {

    suspend fun read(activity: VRActivity, source: PlaybackSource): MediaMetadata? =
        withContext(Dispatchers.IO) {
            val wire = when (source) {
                is PlaybackSource.LocalFile -> activity.nativeReadMediaMetadata(source.path)
                is PlaybackSource.Http -> activity.nativeReadMediaMetadata(source.url)
                is PlaybackSource.Smb -> activity.nativeSmbReadMetadata(
                    source.server.host, source.server.port, source.server.username, source.server.password,
                    source.server.domain, source.server.share, source.path
                )
                is PlaybackSource.Ftp -> activity.nativeFtpReadMetadata(
                    source.server.host, source.server.port, source.server.username, source.server.password, source.path
                )
                is PlaybackSource.Sftp -> activity.nativeSftpReadMetadata(
                    source.server.host, source.server.port, source.server.username, source.server.password,
                    source.server.privateKey ?: "", source.path
                )
                is PlaybackSource.Nfs -> null
            }
            if (wire == null) return@withContext null
            parse(wire)
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
        var detectionConfidence = 3
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
                    detectionConfidence = parts.getOrNull(6)?.toIntOrNull() ?: 3
                }
                "M" -> {
                    val key = parts.getOrNull(1) ?: return@forEach
                    val value = parts.getOrNull(2) ?: return@forEach
                    tags.add(key to value)
                }
                "T" -> {
                    val kind = when (parts.getOrNull(1)) {
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
                            isDefault = parts.getOrNull(12) == "1"
                        )
                    )
                }
            }
        }

        if (container.isEmpty() && tracks.isEmpty()) return null
        return MediaMetadata(container, containerLong, durationMs, bitRate, format3dIndex, detectionConfidence, tags, tracks)
    }
}
