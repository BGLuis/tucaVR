package com.tucavr.download

interface NativeDownloadBridge {
    fun nativeEnqueue(id: String, uri: String, dest: String): Int
    fun nativePause(id: String): Int
    fun nativeResume(id: String): Int
    fun nativeCancel(id: String): Int
    fun nativeGetStats(id: String): LongArray?
    fun nativeSetPlaybackActive(active: Boolean)
}

/**
 * Ponte JNI direta entre Kotlin e a biblioteca nativa vrplayer_native para gerenciamento de downloads.
 */
object DownloadBridge : NativeDownloadBridge {

    init {
        try {
            System.loadLibrary("vrplayer_native")
        } catch (_: UnsatisfiedLinkError) {
            // Em testes unitários JVM no host, a biblioteca nativa não está presente.
        }
    }

    external override fun nativeEnqueue(id: String, uri: String, dest: String): Int
    external override fun nativePause(id: String): Int
    external override fun nativeResume(id: String): Int
    external override fun nativeCancel(id: String): Int
    external override fun nativeGetStats(id: String): LongArray?
    external override fun nativeSetPlaybackActive(active: Boolean)
}
