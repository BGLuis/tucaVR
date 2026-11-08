package com.tucavr.filebrowser

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cache persistido de [MediaMetadata] (dimensões/duração/codec/container, faixas e tags),
 * para exibição rápida na listagem (badge) e abertura instantânea (cache-first) sem
 * recalcular via Rust toda vez. `mediaKey` vem de [CacheKeys.forSource]/[CacheKeys.forLocalEntry].
 */
@Entity(tableName = "media_metadata_cache")
data class MediaMetadataCacheEntry(
    @PrimaryKey val mediaKey: String,
    val container: String,
    val containerLong: String,
    val durationMs: Long,
    val bitRate: Long,
    val format3dIndex: Int,
    val detectionConfidence: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val videoCodec: String,
    val fetchedAt: Long,
    val tracksJson: String = "",
    val tagsJson: String = "",
    val fileSizeBytes: Long = 0L,
)

fun MediaMetadataCacheEntry.toMediaMetadata(): MediaMetadata {
    return MediaMetadata(
        container = container,
        containerLong = containerLong,
        durationMs = durationMs,
        bitRate = bitRate,
        format3dIndex = format3dIndex,
        detectionConfidence = detectionConfidence,
        tags = deserializeTags(tagsJson),
        tracks = deserializeTracks(tracksJson),
    )
}

fun serializeTracks(tracks: List<TrackInfo>): String {
    val arr = JSONArray()
    for (t in tracks) {
        val obj = JSONObject()
        obj.put("kind", t.kind.name)
        obj.put("ordinal", t.ordinal)
        obj.put("codec", t.codec)
        obj.put("language", t.language)
        obj.put("title", t.title)
        obj.put("width", t.width)
        obj.put("height", t.height)
        obj.put("fpsMilli", t.fpsMilli)
        obj.put("channels", t.channels)
        obj.put("sampleRate", t.sampleRate)
        obj.put("bitRate", t.bitRate)
        obj.put("isDefault", t.isDefault)
        arr.put(obj)
    }
    return arr.toString()
}

fun deserializeTracks(jsonStr: String): List<TrackInfo> {
    if (jsonStr.isBlank()) return emptyList()
    return try {
        val list = mutableListOf<TrackInfo>()
        val arr = JSONArray(jsonStr)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val kindStr = obj.optString("kind", "VIDEO")
            val kind =
                try {
                    TrackKind.valueOf(kindStr)
                } catch (_: Exception) {
                    TrackKind.VIDEO
                }
            list.add(
                TrackInfo(
                    kind = kind,
                    ordinal = obj.optInt("ordinal", 0),
                    codec = obj.optString("codec", ""),
                    language = obj.optString("language", ""),
                    title = obj.optString("title", ""),
                    width = obj.optInt("width", 0),
                    height = obj.optInt("height", 0),
                    fpsMilli = obj.optInt("fpsMilli", 0),
                    channels = obj.optInt("channels", 0),
                    sampleRate = obj.optInt("sampleRate", 0),
                    bitRate = obj.optLong("bitRate", 0L),
                    isDefault = obj.optBoolean("isDefault", false),
                ),
            )
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}

fun serializeTags(tags: List<Pair<String, String>>): String {
    val arr = JSONArray()
    for ((k, v) in tags) {
        val obj = JSONObject()
        obj.put("k", k)
        obj.put("v", v)
        arr.put(obj)
    }
    return arr.toString()
}

fun deserializeTags(jsonStr: String): List<Pair<String, String>> {
    if (jsonStr.isBlank()) return emptyList()
    return try {
        val list = mutableListOf<Pair<String, String>>()
        val arr = JSONArray(jsonStr)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val k = obj.optString("k", "")
            val v = obj.optString("v", "")
            if (k.isNotEmpty()) {
                list.add(k to v)
            }
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}

@Dao
interface MediaMetadataCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MediaMetadataCacheEntry)

    @Query("SELECT * FROM media_metadata_cache WHERE mediaKey = :key LIMIT 1")
    suspend fun find(key: String): MediaMetadataCacheEntry?
}
