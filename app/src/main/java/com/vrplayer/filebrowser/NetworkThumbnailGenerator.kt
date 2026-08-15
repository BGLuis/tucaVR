package com.vrplayer.filebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vrplayer.VRActivity
import com.vrplayer.navigation.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

// Contraparte de rede do ThumbnailGenerator local (ver esse arquivo para o
// raciocinio do cache em disco). A geracao em si e feita do lado Rust
// (core::thumbnail::generate, chamada via nativeSmb/Ftp/SftpGenerateThumbnail
// -- ver vr_player_app.cpp) porque so o Rust tem acesso ao Demuxer
// SMB/FTP/SFTP; este objeto so cuida de cache em disco + conversao RGBA -> Bitmap.
object NetworkThumbnailGenerator {

    private const val THUMB_WIDTH = 256
    private const val THUMB_HEIGHT = 144
    private const val CACHE_DIR_NAME = "network_thumbnails"

    suspend fun getThumbnail(context: Context, activity: VRActivity, source: PlaybackSource): Bitmap? {
        return withContext(Dispatchers.IO) {
            val cacheFile = cacheFileFor(context, source)

            val cached = if (cacheFile.exists()) BitmapFactory.decodeFile(cacheFile.absolutePath) else null
            if (cached != null) return@withContext cached

            val rgba = generateRgba(activity, source) ?: return@withContext null
            val bitmap = Bitmap.createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            }
            writeToCache(bitmap, cacheFile)
            bitmap
        }
    }

    // Chamada BLOQUEANTE (rede + decode sincronos do lado Rust) -- so deve
    // ser chamada de Dispatchers.IO, mesma ressalva documentada em
    // rust/bridge/src/lib.rs junto de smb_generate_thumbnail.
    private fun generateRgba(activity: VRActivity, source: PlaybackSource): ByteArray? {
        return when (source) {
            is PlaybackSource.Smb -> activity.nativeSmbGenerateThumbnail(
                source.server.host, source.server.port, source.server.username, source.server.password,
                source.server.domain, source.server.share, source.path, THUMB_WIDTH, THUMB_HEIGHT
            )
            is PlaybackSource.Ftp -> activity.nativeFtpGenerateThumbnail(
                source.server.host, source.server.port, source.server.username, source.server.password,
                source.path, THUMB_WIDTH, THUMB_HEIGHT
            )
            is PlaybackSource.Sftp -> activity.nativeSftpGenerateThumbnail(
                source.server.host, source.server.port, source.server.username, source.server.password,
                source.server.privateKey ?: "", source.path, THUMB_WIDTH, THUMB_HEIGHT
            )
            is PlaybackSource.LocalFile, is PlaybackSource.Http -> null
        }
    }

    private fun writeToCache(bitmap: Bitmap, cacheFile: File) {
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        } catch (e: Exception) {
            // Falha ao persistir o cache nao e fatal -- o chamador ainda
            // recebe o bitmap, so tem que gerar de novo na proxima vez.
        }
    }

    private fun cacheFileFor(context: Context, source: PlaybackSource): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        return File(cacheDir, "${cacheKeyFor(source)}.jpg")
    }

    // Extraida como funcao pura testavel na JVM, mesmo padrao de
    // ThumbnailGenerator.cacheKeyFor (ver ThumbnailGeneratorCacheKeyTest).
    // sizeBytes entra na chave pelo mesmo motivo do lado local: um arquivo
    // trocado no mesmo path nao deve reusar uma miniatura obsoleta. Nao ha
    // um lastModified confiavel na listagem de rede (SMB/FTP/SFTP so
    // devolvem nome/tipo/tamanho -- ver loadNetworkDirectory), entao
    // tamanho e a unica pista de "arquivo mudou" disponivel aqui.
    internal fun cacheKeyFor(source: PlaybackSource): String {
        val raw = when (source) {
            is PlaybackSource.Smb ->
                "smb|${source.server.host}|${source.server.port}|${source.server.share}|${source.path}|${source.sizeBytes}"
            is PlaybackSource.Ftp ->
                "ftp|${source.server.host}|${source.server.port}|${source.path}|${source.sizeBytes}"
            is PlaybackSource.Sftp ->
                "sftp|${source.server.host}|${source.server.port}|${source.path}|${source.sizeBytes}"
            is PlaybackSource.LocalFile, is PlaybackSource.Http ->
                throw IllegalArgumentException("NetworkThumbnailGenerator nao suporta $source")
        }
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
