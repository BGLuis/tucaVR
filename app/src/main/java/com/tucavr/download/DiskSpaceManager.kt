package com.tucavr.download

import android.os.StatFs
import java.io.File
import java.util.Locale

data class DiskSpaceInfo(
    val availableBytes: Long,
    val totalBytes: Long
) {
    val freePercentage: Double
        get() = if (totalBytes > 0) (availableBytes.toDouble() / totalBytes.toDouble()) * 100.0 else 0.0
}

/**
 * Gestão e monitoramento de espaço em disco no Quest 3 (Fase 0.4 Seção 4 / T4.6).
 *
 * Garante que downloads não estourem o armazenamento do dispositivo e alerta o usuário
 * quando o espaço livre for inferior a 2 GB ou 10%.
 */
class DiskSpaceManager(
    private val spaceProvider: (File) -> DiskSpaceInfo = { dir ->
        val stat = StatFs(dir.path)
        DiskSpaceInfo(stat.availableBytes, stat.totalBytes)
    }
) {
    companion object {
        const val SAFETY_BUFFER_BYTES: Long = 2_000_000_000L // 2 GB
        const val LOW_STORAGE_PERCENTAGE: Double = 10.0 // 10%
        const val CRITICAL_STORAGE_BYTES: Long = 2_000_000_000L // 2 GB

        fun formatBytes(bytes: Long): String {
            val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return if (gb >= 1.0) {
                String.format(Locale.US, "%.1f GB", gb)
            } else {
                val mb = bytes.toDouble() / (1024.0 * 1024.0)
                String.format(Locale.US, "%.1f MB", mb)
            }
        }
    }

    fun getDiskSpaceInfo(dir: File): DiskSpaceInfo {
        return try {
            spaceProvider(dir)
        } catch (_: Exception) {
            DiskSpaceInfo(availableBytes = 0L, totalBytes = 0L)
        }
    }

    fun hasSufficientSpace(dir: File, requiredBytes: Long): Boolean {
        val info = getDiskSpaceInfo(dir)
        if (info.totalBytes == 0L) return true // Não conseguiu ler, permite
        return info.availableBytes >= (requiredBytes + SAFETY_BUFFER_BYTES)
    }

    fun isLowStorage(dir: File): Boolean {
        val info = getDiskSpaceInfo(dir)
        if (info.totalBytes == 0L) return false
        return info.availableBytes < CRITICAL_STORAGE_BYTES || info.freePercentage < LOW_STORAGE_PERCENTAGE
    }
}
