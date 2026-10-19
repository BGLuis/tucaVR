package com.tucavr.filebrowser

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

/** Validation for DLNA `albumArtURI` values, which come from the media server and are untrusted. */
internal object DlnaThumbnailPolicy {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_SOURCE_PIXELS = 64_000_000L

    fun isFetchableUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    fun isImageContentType(contentType: String?): Boolean {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return mime.startsWith("image/") && mime != "image/svg+xml"
    }

    /** [declaredLength] is -1 when the server did not send Content-Length. */
    fun acceptsHeaders(
        contentType: String?,
        declaredLength: Long,
    ): Boolean = isImageContentType(contentType) && declaredLength <= MAX_BYTES

    /** Returns null as soon as more than [maxBytes] have been read, without consuming the rest of the stream. */
    fun readCapped(
        input: InputStream,
        maxBytes: Int = MAX_BYTES,
    ): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun isDecodableSize(
        width: Int,
        height: Int,
    ): Boolean = width > 0 && height > 0 && width.toLong() * height <= MAX_SOURCE_PIXELS

    fun sampleSizeFor(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }
}
