package com.tucavr.history

import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpServer
import com.tucavr.network.SavedServer
import com.tucavr.network.ServerProtocol
import com.tucavr.network.SftpServer
import com.tucavr.network.SmbServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySourceResolverTest {
    private val smbServer =
        SmbServer(
            id = "smb-1",
            name = "NAS",
            host = "192.168.1.100",
            port = 445,
            share = "movies",
            username = "user",
            password = "pass",
            domain = "",
        )
    private val ftpServer =
        FtpServer(
            id = "ftp-1",
            name = "FTP Host",
            host = "192.168.1.101",
            port = 21,
            username = "user",
            password = "pass",
        )
    private val sftpServer =
        SftpServer(
            id = "sftp-1",
            name = "SFTP Host",
            host = "192.168.1.102",
            port = 22,
            username = "user",
            password = "pass",
            privateKey = null,
        )
    private val nfsServer =
        SavedServer(
            id = "nfs-1",
            name = "NFS Share",
            protocol = ServerProtocol.NFS,
            host = "192.168.1.103",
            port = 2049,
            path = "/exports/video",
        )
    private val webdavServer =
        SavedServer(
            id = "webdav-1",
            name = "WebDAV Cloud",
            protocol = ServerProtocol.WEBDAV,
            host = "192.168.1.104",
            port = 8080,
            path = "/remote.php/webdav",
        )

    private fun createEntry(
        sourceType: HistorySourceType,
        historyKey: String,
        mediaPath: String = "sub/clip.mp4",
        serverInfo: String? = """{"serverId":"test-id"}""",
    ) = PlaybackHistory(
        historyKey = historyKey,
        title = "Test Clip",
        mediaPath = mediaPath,
        positionMs = 15_000L,
        durationMs = 90_000L,
        lastPlayedAt = 1_000_000L,
        thumbnailPath = null,
        sourceType = sourceType,
        serverInfo = serverInfo,
    )

    @Test
    fun `resolves SMB entry with valid serverId and extracts sizeBytes from key`() =
        runTest {
            val entry =
                createEntry(
                    sourceType = HistorySourceType.SMB,
                    historyKey = "smb|NAS|movies|sub/clip.mp4|10485760",
                    serverInfo = """{"serverId":"smb-1","name":"NAS"}""",
                )

            val resolved =
                HistorySourceResolver.resolveNetworkSourceInternal(
                    entry = entry,
                    findSmb = { id -> if (id == "smb-1") smbServer else null },
                    findFtp = { null },
                    findSftp = { null },
                    findNfs = { null },
                    findWebdav = { null },
                )

            assertNotNull(resolved)
            assertTrue(resolved is PlaybackSource.Smb)
            val smbSource = resolved as PlaybackSource.Smb
            assertEquals(smbServer, smbSource.server)
            assertEquals("sub/clip.mp4", smbSource.path)
            assertEquals(10485760L, smbSource.sizeBytes)
        }

    @Test
    fun `resolves FTP entry with valid serverId and extracts sizeBytes`() =
        runTest {
            val entry =
                createEntry(
                    sourceType = HistorySourceType.FTP,
                    historyKey = "ftp|FTP Host|sub/clip.mp4|2097152",
                    serverInfo = """{"serverId":"ftp-1"}""",
                )

            val resolved =
                HistorySourceResolver.resolveNetworkSourceInternal(
                    entry = entry,
                    findSmb = { null },
                    findFtp = { id -> if (id == "ftp-1") ftpServer else null },
                    findSftp = { null },
                    findNfs = { null },
                    findWebdav = { null },
                )

            assertNotNull(resolved)
            assertTrue(resolved is PlaybackSource.Ftp)
            val ftpSource = resolved as PlaybackSource.Ftp
            assertEquals(ftpServer, ftpSource.server)
            assertEquals("sub/clip.mp4", ftpSource.path)
            assertEquals(2097152L, ftpSource.sizeBytes)
        }

    @Test
    fun `resolves SFTP entry with valid serverId and extracts sizeBytes`() =
        runTest {
            val entry =
                createEntry(
                    sourceType = HistorySourceType.SFTP,
                    historyKey = "sftp|SFTP Host|sub/clip.mp4|3145728",
                    serverInfo = """{"serverId":"sftp-1"}""",
                )

            val resolved =
                HistorySourceResolver.resolveNetworkSourceInternal(
                    entry = entry,
                    findSmb = { null },
                    findFtp = { null },
                    findSftp = { id -> if (id == "sftp-1") sftpServer else null },
                    findNfs = { null },
                    findWebdav = { null },
                )

            assertNotNull(resolved)
            assertTrue(resolved is PlaybackSource.Sftp)
            val sftpSource = resolved as PlaybackSource.Sftp
            assertEquals(sftpServer, sftpSource.server)
            assertEquals("sub/clip.mp4", sftpSource.path)
            assertEquals(3145728L, sftpSource.sizeBytes)
        }

    @Test
    fun `resolves NFS entry with valid serverId and extracts sizeBytes`() =
        runTest {
            val entry =
                createEntry(
                    sourceType = HistorySourceType.NFS,
                    historyKey = "nfs|NFS Share|/exports/video|sub/clip.mp4|4194304",
                    serverInfo = """{"serverId":"nfs-1"}""",
                )

            val resolved =
                HistorySourceResolver.resolveNetworkSourceInternal(
                    entry = entry,
                    findSmb = { null },
                    findFtp = { null },
                    findSftp = { null },
                    findNfs = { id -> if (id == "nfs-1") nfsServer else null },
                    findWebdav = { null },
                )

            assertNotNull(resolved)
            assertTrue(resolved is PlaybackSource.Nfs)
            val nfsSource = resolved as PlaybackSource.Nfs
            assertEquals(nfsServer, nfsSource.server)
            assertEquals("sub/clip.mp4", nfsSource.path)
            assertEquals(4194304L, nfsSource.sizeBytes)
        }

    @Test
    fun `resolves WebDAV entry with valid serverId and extracts sizeBytes`() =
        runTest {
            val entry =
                createEntry(
                    sourceType = HistorySourceType.WEBDAV,
                    historyKey = "webdav|WebDAV Cloud|/remote.php/webdav|sub/clip.mp4|5242880",
                    serverInfo = """{"serverId":"webdav-1"}""",
                )

            val resolved =
                HistorySourceResolver.resolveNetworkSourceInternal(
                    entry = entry,
                    findSmb = { null },
                    findFtp = { null },
                    findSftp = { null },
                    findNfs = { null },
                    findWebdav = { id -> if (id == "webdav-1") webdavServer else null },
                )

            assertNotNull(resolved)
            assertTrue(resolved is PlaybackSource.Webdav)
            val webdavSource = resolved as PlaybackSource.Webdav
            assertEquals(webdavServer, webdavSource.server)
            assertEquals("sub/clip.mp4", webdavSource.path)
            assertEquals(5242880L, webdavSource.sizeBytes)
        }

    @Test
    fun `returns null when server is not found in store or database`() =
        runTest {
            val entry =
                createEntry(
                    sourceType = HistorySourceType.SMB,
                    historyKey = "smb|NAS|movies|sub/clip.mp4|10485760",
                    serverInfo = """{"serverId":"missing-server"}""",
                )

            val resolved =
                HistorySourceResolver.resolveNetworkSourceInternal(
                    entry = entry,
                    findSmb = { null },
                    findFtp = { null },
                    findSftp = { null },
                    findNfs = { null },
                    findWebdav = { null },
                )

            assertNull(resolved)
        }

    @Test
    fun `returns null for null, empty or corrupted serverInfo`() =
        runTest {
            val testCases =
                listOf(
                    null,
                    "",
                    "    ",
                    "{corrupted json",
                    "{}",
                    """{"serverId":""}""",
                    """{"serverId":"   "}""",
                    """{"serverId":null}""",
                    """{"serverId":"null"}""",
                )

            for (serverInfo in testCases) {
                val entry =
                    createEntry(
                        sourceType = HistorySourceType.SMB,
                        historyKey = "smb|NAS|movies|sub/clip.mp4|10485760",
                        serverInfo = serverInfo,
                    )
                val resolved =
                    HistorySourceResolver.resolveNetworkSourceInternal(
                        entry = entry,
                        findSmb = { smbServer },
                        findFtp = { ftpServer },
                        findSftp = { sftpServer },
                        findNfs = { nfsServer },
                        findWebdav = { webdavServer },
                    )
                assertNull("Expected null for serverInfo: $serverInfo", resolved)
            }
        }

    @Test
    fun `returns null for non-network history types without invoking finders`() =
        runTest {
            val nonNetworkTypes =
                listOf(
                    HistorySourceType.LOCAL,
                    HistorySourceType.HTTP,
                    HistorySourceType.DLNA,
                )

            var findCalled = false
            for (type in nonNetworkTypes) {
                val entry =
                    createEntry(
                        sourceType = type,
                        historyKey = "local|/sdcard/video.mp4|10485760",
                        serverInfo = """{"serverId":"smb-1"}""",
                    )
                val resolved =
                    HistorySourceResolver.resolveNetworkSourceInternal(
                        entry = entry,
                        findSmb = {
                            findCalled = true
                            smbServer
                        },
                        findFtp = {
                            findCalled = true
                            ftpServer
                        },
                        findSftp = {
                            findCalled = true
                            sftpServer
                        },
                        findNfs = {
                            findCalled = true
                            nfsServer
                        },
                        findWebdav = {
                            findCalled = true
                            webdavServer
                        },
                    )
                assertNull("Expected null for non-network type: $type", resolved)
            }
            assertTrue("Finders must never be called for non-network history types", !findCalled)
        }

    @Test
    fun `falls back to sizeBytes 0L when historyKey suffix is non-numeric or missing`() =
        runTest {
            val nonNumericKeyEntry =
                createEntry(
                    sourceType = HistorySourceType.SMB,
                    historyKey = "smb|NAS|movies|sub/clip.mp4|not_a_number",
                    serverInfo = """{"serverId":"smb-1"}""",
                )
            val missingSuffixKeyEntry =
                createEntry(
                    sourceType = HistorySourceType.SMB,
                    historyKey = "smb|NAS|movies|sub/clip.mp4",
                    serverInfo = """{"serverId":"smb-1"}""",
                )

            for (entry in listOf(nonNumericKeyEntry, missingSuffixKeyEntry)) {
                val resolved =
                    HistorySourceResolver.resolveNetworkSourceInternal(
                        entry = entry,
                        findSmb = { smbServer },
                        findFtp = { null },
                        findSftp = { null },
                        findNfs = { null },
                        findWebdav = { null },
                    )
                assertNotNull(resolved)
                val smbSource = resolved as PlaybackSource.Smb
                assertEquals(0L, smbSource.sizeBytes)
            }
        }

    @Test
    fun `extractServerId returns serverId on valid json and null on invalid inputs`() {
        assertEquals("srv-42", HistorySourceResolver.extractServerId("""{"serverId":"srv-42"}"""))
        assertEquals("srv-42", HistorySourceResolver.extractServerId("""{"serverId":"srv-42","name":"NAS"}"""))
        assertNull(HistorySourceResolver.extractServerId(null))
        assertNull(HistorySourceResolver.extractServerId(""))
        assertNull(HistorySourceResolver.extractServerId("   "))
        assertNull(HistorySourceResolver.extractServerId("{invalid"))
        assertNull(HistorySourceResolver.extractServerId("{}"))
        assertNull(HistorySourceResolver.extractServerId("""{"serverId":""}"""))
        assertNull(HistorySourceResolver.extractServerId("""{"serverId":"   "}"""))
        assertNull(HistorySourceResolver.extractServerId("""{"serverId":null}"""))
        assertNull(HistorySourceResolver.extractServerId("""{"serverId":"null"}"""))
    }
}
