package com.tucavr.filebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.tucavr.VRActivity
import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

// Contraparte de rede do ThumbnailGenerator local (ver esse arquivo para o
// raciocinio do cache em disco). A geracao em si e feita do lado Rust
// (core::thumbnail::generate, chamada via nativeSmb/Ftp/SftpGenerateThumbnail
// -- ver vr_player_app.cpp) porque so o Rust tem acesso ao Demuxer
// SMB/FTP/SFTP; este objeto so cuida de cache em disco + conversao RGBA -> Bitmap.
object NetworkThumbnailGenerator {

    const val THUMB_WIDTH = 512
    const val THUMB_HEIGHT = 288
    private const val CACHE_DIR_NAME = "network_thumbnails_v2"

    // Trabalho 1: Gate adaptativo de orçamento de memória para limitar a concorrência
    // e impedir OOM em pastas contendo múltiplos vídeos 8K.
    internal val budgetGate = MemoryBudgetGate()

    // Gate leve para operações de probe de metadados antes da decodificação.
    private val probeSemaphore = Semaphore(4)

    // Gerador de tokens para cancelamento cooperativo instantâneo no Rust.
    private val nextCancelToken = AtomicLong(1L)

    suspend fun getThumbnail(context: Context, activity: VRActivity, source: PlaybackSource): Bitmap? {
        return withContext(Dispatchers.IO) {
            val cacheFile = cacheFileFor(context, source)

            val cached = if (cacheFile.exists()) BitmapFactory.decodeFile(cacheFile.absolutePath) else null
            if (cached != null) return@withContext cached

            if (!coroutineContext.isActive) return@withContext null

            // 1. Fase de Probe com cache em disco do resultado (.meta)
            val key = cacheKeyFor(source)
            val metaCacheFile = File(context.cacheDir, "$CACHE_DIR_NAME/$key.meta")
            val (width, height) = readCachedDimensions(metaCacheFile) ?: probeDimensions(activity, source, metaCacheFile)

            if (!coroutineContext.isActive) return@withContext null

            // 2. Estima o custo de memória nativa
            val costBytes = MemoryBudgetGate.calculateCostBytes(width, height)

            // 3. Registra cancelToken para cancelamento cooperativo no Rust via invokeOnCompletion
            val cancelToken = nextCancelToken.incrementAndGet()
            coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    activity.nativeCancelThumbnailGeneration(cancelToken)
                }
            }

            // 4. Adquire orçamento no gate e decodifica
            val rgba = budgetGate.withBudget(costBytes) {
                if (!coroutineContext.isActive) return@withBudget null
                generateRgba(activity, source, cancelToken)
            } ?: return@withContext null

            val bitmap = Bitmap.createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            }
            writeToCache(bitmap, cacheFile)
            bitmap
        }
    }

    private fun readCachedDimensions(metaFile: File): Pair<Int, Int>? {
        return runCatching {
            if (!metaFile.exists()) return null
            val line = metaFile.readText().trim()
            val parts = line.split("x")
            if (parts.size == 2) {
                val w = parts[0].toIntOrNull() ?: return null
                val h = parts[1].toIntOrNull() ?: return null
                Pair(w, h)
            } else null
        }.getOrNull()
    }

    private suspend fun probeDimensions(activity: VRActivity, source: PlaybackSource, metaFile: File): Pair<Int, Int> {
        return probeSemaphore.withPermit {
            readCachedDimensions(metaFile)?.let { return@withPermit it }

            val meta = runCatching { MediaMetadataReader.read(activity, source) }.getOrNull()
            val videoTrack = meta?.videoTracks?.firstOrNull()
            val w = videoTrack?.width ?: 0
            val h = videoTrack?.height ?: 0

            if (w > 0 && h > 0) {
                runCatching {
                    metaFile.parentFile?.mkdirs()
                    metaFile.writeText("${w}x${h}")
                }
            }
            Pair(w, h)
        }
    }

    // Chamada BLOQUEANTE (rede + decode sincronos do lado Rust) -- so deve
    // ser chamada de Dispatchers.IO, mesma ressalva documentada em
    // rust/bridge/src/lib.rs junto de smb_generate_thumbnail.
    private fun generateRgba(activity: VRActivity, source: PlaybackSource, cancelToken: Long): ByteArray? {
        return when (source) {
            is PlaybackSource.Smb -> activity.nativeSmbGenerateThumbnail(
                source.server.host, source.server.port, source.server.username, source.server.password,
                source.server.domain, source.server.share, source.path, THUMB_WIDTH, THUMB_HEIGHT, cancelToken
            )
            is PlaybackSource.Ftp -> activity.nativeFtpGenerateThumbnail(
                source.server.host, source.server.port, source.server.username, source.server.password,
                source.path, THUMB_WIDTH, THUMB_HEIGHT, cancelToken
            )
            is PlaybackSource.Sftp -> activity.nativeSftpGenerateThumbnail(
                source.server.host, source.server.port, source.server.username, source.server.password,
                source.server.privateKey ?: "", source.path, THUMB_WIDTH, THUMB_HEIGHT, cancelToken
            )
            is PlaybackSource.LocalFile, is PlaybackSource.Http, is PlaybackSource.Nfs, is PlaybackSource.Dlna -> null
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
            is PlaybackSource.Dlna ->
                "dlna|${source.server.host}|${source.url}|${source.sizeBytes}"
            is PlaybackSource.LocalFile, is PlaybackSource.Http, is PlaybackSource.Nfs ->
                throw IllegalArgumentException("NetworkThumbnailGenerator nao suporta $source")
        }
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // --- Preview de arrasto no seekbar (T-seek-ux) ---
    //
    // Trilha esparsa de miniaturas BEM menores que THUMB_WIDTH/HEIGHT acima
    // (80x45 em vez de 256x144) — um preview de arrasto fica na tela por
    // milissegundos, nao precisa da mesma resolucao da miniatura "hero" da
    // listagem, e o tamanho em disco escala linear com resolucao x numero
    // de frames (a cada SCRUB_INTERVAL_SECONDS): num filme de 2h a 15s de
    // intervalo isso já são ~480 frames — em 256x144 seriam ~70MB de cache
    // POR VIDEO, inviavel; em 80x45 fica ~7MB, razoavel.
    //
    // Guardado como UM blob RGBA cru concatenado (sem compressao JPEG por
    // frame) — mais simples que gerenciar N arquivos, e o Rust ja entrega
    // RGBA puro, entao nao ha decode/encode extra em nenhuma ponta; so
    // corta um Bitmap sob demanda (bitmapAt) na hora de exibir.
    private const val SCRUB_WIDTH = 80
    private const val SCRUB_HEIGHT = 45
    private const val SCRUB_INTERVAL_SECONDS = 15f
    private const val SCRUB_CACHE_DIR_NAME = "network_scrub_strips"

    // Cache em memoria dimensionado por bytes (64 MiB) para evitar vazamento
    // em sessões longas, com evicção automática LRU.
    private val scrubMemoryCache = object : LruCache<String, ScrubStrip>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ScrubStrip): Int = value.byteCount
    }

    /**
     * Gera (ou reaproveita do cache) a trilha de preview de arrasto pra
     * ESTE video. So SMB/SFTP tem geracao de thumbnail hoje (mesma
     * limitacao de [getThumbnail] acima — FTP/HTTPS/local nao passam por
     * aqui, ver [generateScrubRgba]). Chamada BLOQUEANTE na primeira vez
     * (rede + N decodes sincronos do lado Rust) — SEMPRE de
     * `Dispatchers.IO`; chamadas seguintes pro MESMO video (cache hit, em
     * memoria ou disco) retornam rapido.
     */
    suspend fun getScrubStrip(context: Context, activity: VRActivity, source: PlaybackSource): ScrubStrip? {
        val key = runCatching { cacheKeyFor(source) }.getOrNull() ?: return null
        scrubMemoryCache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val cacheFile = scrubCacheFileFor(context, key)
            val cachedRgba = if (cacheFile.exists()) runCatching { cacheFile.readBytes() }.getOrNull() else null
            val rgba = cachedRgba ?: generateScrubRgba(activity, source)?.also { writeScrubCache(it, cacheFile) }
            val strip = rgba?.let { ScrubStrip(it, SCRUB_WIDTH, SCRUB_HEIGHT, SCRUB_INTERVAL_SECONDS) } ?: return@withContext null
            scrubMemoryCache.put(key, strip)
            strip
        }
    }

    private fun generateScrubRgba(activity: VRActivity, source: PlaybackSource): ByteArray? {
        return when (source) {
            is PlaybackSource.Smb -> activity.nativeSmbGenerateThumbnailStrip(
                source.server.host, source.server.port, source.server.username, source.server.password,
                source.server.domain, source.server.share, source.path,
                SCRUB_INTERVAL_SECONDS, SCRUB_WIDTH, SCRUB_HEIGHT
            )
            is PlaybackSource.Sftp -> activity.nativeSftpGenerateThumbnailStrip(
                source.server.host, source.server.port, source.server.username, source.server.password,
                source.server.privateKey ?: "", source.path,
                SCRUB_INTERVAL_SECONDS, SCRUB_WIDTH, SCRUB_HEIGHT
            )
            is PlaybackSource.Ftp, is PlaybackSource.LocalFile, is PlaybackSource.Http, is PlaybackSource.Nfs, is PlaybackSource.Dlna -> null
        }
    }

    private fun writeScrubCache(rgba: ByteArray, cacheFile: File) {
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(rgba)
        } catch (e: Exception) {
            // Falha ao persistir nao e fatal -- mesma tolerancia de writeToCache acima.
        }
    }

    private fun scrubCacheFileFor(context: Context, key: String): File {
        val cacheDir = File(context.cacheDir, SCRUB_CACHE_DIR_NAME)
        return File(cacheDir, "$key.rgba")
    }
}

/**
 * Trilha de frames RGBA de mesma resolucao, concatenados — ver
 * [NetworkThumbnailGenerator.getScrubStrip]. A posicao do frame `i` e
 * sempre `(i + 1) * intervalSeconds` (mesma convencao do lado Rust,
 * `core::thumbnail::generate_strip`).
 */
class ScrubStrip(private val rgba: ByteArray, val frameWidth: Int, val frameHeight: Int, val intervalSeconds: Float) {
    private val frameBytes = frameWidth * frameHeight * 4
    val count: Int get() = if (frameBytes == 0) 0 else rgba.size / frameBytes
    val byteCount: Int get() = rgba.size

    /** Frame mais proximo de `positionSeconds`, ou null se a trilha estiver vazia. */
    fun bitmapAt(positionSeconds: Float): Bitmap? {
        if (count == 0) return null
        val index = (positionSeconds / intervalSeconds).toInt().coerceIn(0, count - 1)
        val start = index * frameBytes
        return Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(rgba, start, frameBytes))
        }
    }

    /** Bytes RGBA crus do frame mais proximo de `positionSeconds`, sem passar por Bitmap
     * (ver VRActivity.nativeUpdateScrubOverlay). Null se a trilha estiver vazia. */
    fun rgbaAt(positionSeconds: Float): ByteArray? {
        if (count == 0) return null
        val index = (positionSeconds / intervalSeconds).toInt().coerceIn(0, count - 1)
        val start = index * frameBytes
        return rgba.copyOfRange(start, start + frameBytes)
    }
}
