package com.tucavr.download

import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Testes unitários para [DownloadRepository] (Fase 0.4 Seção 4).
 * Valida o ciclo de vida completo de downloads com DAOs e Bridge simulados na JVM.
 */
class DownloadRepositoryTest {

    private class FakeDownloadDao : DownloadDao {
        val downloads = mutableListOf<Download>()

        override suspend fun upsert(download: Download) {
            downloads.removeAll { it.id == download.id }
            downloads.add(download)
        }

        override suspend fun findById(id: String): Download? {
            return downloads.firstOrNull { it.id == id }
        }

        override suspend fun listAll(): List<Download> {
            return downloads.toList()
        }

        override suspend fun listActive(): List<Download> {
            return downloads.filter { it.state == DownloadStatus.DOWNLOADING || it.state == DownloadStatus.QUEUED }
        }

        override suspend fun updateProgress(id: String, downloadedBytes: Long, state: String) {
            val idx = downloads.indexOfFirst { it.id == id }
            if (idx != -1) {
                val current = downloads[idx]
                downloads[idx] = current.copy(downloadedBytes = downloadedBytes, state = state)
            }
        }

        override suspend fun updateCompleted(id: String, completedAt: Long, state: String) {
            val idx = downloads.indexOfFirst { it.id == id }
            if (idx != -1) {
                val current = downloads[idx]
                downloads[idx] = current.copy(completedAt = completedAt, state = state, downloadedBytes = current.totalBytes)
            }
        }

        override suspend fun updateFailed(id: String, errorMessage: String, state: String) {
            val idx = downloads.indexOfFirst { it.id == id }
            if (idx != -1) {
                val current = downloads[idx]
                downloads[idx] = current.copy(errorMessage = errorMessage, state = state)
            }
        }

        override suspend fun deleteById(id: String) {
            downloads.removeAll { it.id == id }
        }

        override suspend fun clearCompleted() {
            downloads.removeAll { it.state == DownloadStatus.COMPLETED }
        }
    }

    private class FakeNativeDownloadBridge : NativeDownloadBridge {
        val enqueued = mutableListOf<Triple<String, String, String>>()
        val paused = mutableListOf<String>()
        val resumed = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val statsMap = mutableMapOf<String, LongArray>()
        var playbackActive: Boolean = false

        override fun nativeEnqueue(id: String, uri: String, dest: String): Int {
            enqueued.add(Triple(id, uri, dest))
            return 0
        }

        override fun nativePause(id: String): Int {
            paused.add(id)
            return 0
        }

        override fun nativeResume(id: String): Int {
            resumed.add(id)
            return 0
        }

        override fun nativeCancel(id: String): Int {
            cancelled.add(id)
            return 0
        }

        override fun nativeGetStats(id: String): LongArray? {
            return statsMap[id]
        }

        override fun nativeSetPlaybackActive(active: Boolean) {
            playbackActive = active
        }
    }

    @Test
    fun `enqueue rejects download when disk space is insufficient`() = runTest {
        val fakeDao = FakeDownloadDao()
        val fakeBridge = FakeNativeDownloadBridge()
        // 1 GB disponível no disco, menor que o buffer de 2 GB
        val diskSpaceManager = DiskSpaceManager(
            spaceProvider = { DiskSpaceInfo(availableBytes = 1L * 1024 * 1024 * 1024, totalBytes = 64L * 1024 * 1024 * 1024) }
        )

        val repo = DownloadRepository(
            context = null,
            dao = fakeDao,
            bridge = fakeBridge,
            diskSpaceManager = diskSpaceManager
        )

        val source = PlaybackSource.Http("https://example.com/movie.mp4")
        val result = repo.enqueue(source, "movie.mp4", sourceSize = 500L * 1024 * 1024)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
        assertEquals(0, fakeDao.downloads.size)
        assertEquals(0, fakeBridge.enqueued.size)
    }

    @Test
    fun `enqueue succeeds when space is sufficient and notifies bridge and dao`() = runTest {
        val fakeDao = FakeDownloadDao()
        val fakeBridge = FakeNativeDownloadBridge()
        // 20 GB disponível no disco
        val diskSpaceManager = DiskSpaceManager(
            spaceProvider = { DiskSpaceInfo(availableBytes = 20L * 1024 * 1024 * 1024, totalBytes = 64L * 1024 * 1024 * 1024) }
        )

        val repo = DownloadRepository(
            context = null,
            dao = fakeDao,
            bridge = fakeBridge,
            diskSpaceManager = diskSpaceManager
        )

        val source = PlaybackSource.Http("https://example.com/sample.mp4")
        val tempDir = File(System.getProperty("java.io.tmpdir"), "tucavr_test_dl")
        tempDir.mkdirs()

        val result = repo.enqueue(source, "sample.mp4", sourceSize = 100L * 1024 * 1024, customDestDir = tempDir)

        assertTrue(result.isSuccess)
        val download = result.getOrNull()
        assertNotNull(download)
        assertEquals("sample.mp4", download?.displayName)
        assertEquals(DownloadStatus.QUEUED, download?.state)
        assertEquals(1, fakeDao.downloads.size)
        assertEquals(1, fakeBridge.enqueued.size)
    }

    @Test
    fun `pause updates dao and notifies bridge`() = runTest {
        val fakeDao = FakeDownloadDao()
        val fakeBridge = FakeNativeDownloadBridge()
        val repo = DownloadRepository(
            context = null,
            dao = fakeDao,
            bridge = fakeBridge,
            diskSpaceManager = DiskSpaceManager(spaceProvider = { DiskSpaceInfo(100L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024) })
        )

        val d = Download(
            id = "d1",
            sourceUri = "http://example.com/v.mp4",
            sourceType = "HTTP",
            destinationPath = "/tmp/v.mp4",
            displayName = "v.mp4",
            totalBytes = 1000L,
            downloadedBytes = 200L,
            state = DownloadStatus.DOWNLOADING,
            createdAt = 100L
        )
        fakeDao.upsert(d)

        repo.pause("d1")

        assertEquals(listOf("d1"), fakeBridge.paused)
        val updated = fakeDao.findById("d1")
        assertEquals(DownloadStatus.PAUSED, updated?.state)
    }

    @Test
    fun `resume updates dao and notifies bridge`() = runTest {
        val fakeDao = FakeDownloadDao()
        val fakeBridge = FakeNativeDownloadBridge()
        val repo = DownloadRepository(
            context = null,
            dao = fakeDao,
            bridge = fakeBridge,
            diskSpaceManager = DiskSpaceManager(spaceProvider = { DiskSpaceInfo(100L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024) })
        )

        val d = Download(
            id = "d2",
            sourceUri = "http://example.com/v2.mp4",
            sourceType = "HTTP",
            destinationPath = "/tmp/v2.mp4",
            displayName = "v2.mp4",
            totalBytes = 1000L,
            downloadedBytes = 500L,
            state = DownloadStatus.PAUSED,
            createdAt = 100L
        )
        fakeDao.upsert(d)

        repo.resume("d2")

        assertEquals(listOf("d2"), fakeBridge.resumed)
        val updated = fakeDao.findById("d2")
        assertEquals(DownloadStatus.QUEUED, updated?.state)
    }

    @Test
    fun `cancel updates dao and notifies bridge`() = runTest {
        val fakeDao = FakeDownloadDao()
        val fakeBridge = FakeNativeDownloadBridge()
        val repo = DownloadRepository(
            context = null,
            dao = fakeDao,
            bridge = fakeBridge,
            diskSpaceManager = DiskSpaceManager(spaceProvider = { DiskSpaceInfo(100L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024) })
        )

        val d = Download(
            id = "d3",
            sourceUri = "http://example.com/v3.mp4",
            sourceType = "HTTP",
            destinationPath = "/tmp/v3.mp4",
            displayName = "v3.mp4",
            totalBytes = 1000L,
            downloadedBytes = 100L,
            state = DownloadStatus.DOWNLOADING,
            createdAt = 100L
        )
        fakeDao.upsert(d)

        repo.cancel("d3")

        assertEquals(listOf("d3"), fakeBridge.cancelled)
        val updated = fakeDao.findById("d3")
        assertEquals(DownloadStatus.CANCELLED, updated?.state)
    }

    @Test
    fun `syncActiveDownloads updates progress and marks completed when bridge reports completion`() = runTest {
        val fakeDao = FakeDownloadDao()
        val fakeBridge = FakeNativeDownloadBridge()
        val repo = DownloadRepository(
            context = null,
            dao = fakeDao,
            bridge = fakeBridge,
            diskSpaceManager = DiskSpaceManager(spaceProvider = { DiskSpaceInfo(100L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024) })
        )

        val d = Download(
            id = "d4",
            sourceUri = "http://example.com/v4.mp4",
            sourceType = "HTTP",
            destinationPath = "/tmp/v4.mp4",
            displayName = "v4.mp4",
            totalBytes = 1000L,
            downloadedBytes = 500L,
            state = DownloadStatus.DOWNLOADING,
            createdAt = 100L
        )
        fakeDao.upsert(d)

        // Simula stats do motor nativo: 1000 bytes baixados, 1000 total, 0 velocidade, estado 3 = COMPLETED
        fakeBridge.statsMap["d4"] = longArrayOf(1000L, 1000L, 0L, 3L)

        repo.syncActiveDownloads()

        // Como foi concluído, o DAO deve ter sido atualizado para COMPLETED
        val updated = fakeDao.findById("d4")
        assertEquals(DownloadStatus.COMPLETED, updated?.state)
        assertEquals(1000L, updated?.downloadedBytes)
    }
}
