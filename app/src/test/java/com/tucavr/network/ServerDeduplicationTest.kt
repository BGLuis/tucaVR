package com.tucavr.network

import com.tucavr.screens.DiscoveredServerItem
import com.tucavr.screens.findSavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for network server discovery deduplication (Issue #85).
 * Validates that findSavedServer accurately matches servers by (protocol, host, port)
 * case-insensitively, while ignoring mutable display names and preventing duplicate entries.
 */
class ServerDeduplicationTest {
    @Test
    fun findSavedServerMatchesExactProtocolHostAndPort() {
        val saved =
            listOf(
                SavedServer(
                    id = "server-1",
                    name = "My NAS",
                    protocol = ServerProtocol.NFS,
                    host = "192.168.1.100",
                    port = 2049,
                ),
            )
        val discovered =
            DiscoveredServerItem(
                protocol = ServerProtocol.NFS,
                name = "Discovered NFS",
                host = "192.168.1.100",
                port = 2049,
            )

        val match = findSavedServer(saved, discovered)
        assertNotNull(match)
        assertEquals("server-1", match?.id)
    }

    @Test
    fun findSavedServerMatchesHostCaseInsensitively() {
        val saved =
            listOf(
                SavedServer(
                    id = "server-smb",
                    name = "Samba Server",
                    protocol = ServerProtocol.SMB,
                    host = "mynas.local",
                    port = 445,
                ),
            )
        val discovered =
            DiscoveredServerItem(
                protocol = ServerProtocol.SMB,
                name = "SAMBA",
                host = "MYNAS.LOCAL",
                port = 445,
            )

        val match = findSavedServer(saved, discovered)
        assertNotNull(match)
        assertEquals("server-smb", match?.id)
    }

    @Test
    fun findSavedServerIgnoresDisplayNameDifferences() {
        val saved =
            listOf(
                SavedServer(
                    id = "server-dlna",
                    name = "Old DLNA Name",
                    protocol = ServerProtocol.DLNA,
                    host = "192.168.1.50",
                    port = 8200,
                ),
            )
        val discovered =
            DiscoveredServerItem(
                protocol = ServerProtocol.DLNA,
                name = "Brand New DLNA Media Server",
                host = "192.168.1.50",
                port = 8200,
            )

        val match = findSavedServer(saved, discovered)
        assertNotNull(match)
        assertEquals("server-dlna", match?.id)
    }

    @Test
    fun findSavedServerReturnsNullWhenPortDiffers() {
        val saved =
            listOf(
                SavedServer(
                    id = "server-ftp-1",
                    name = "FTP Main",
                    protocol = ServerProtocol.FTP,
                    host = "192.168.1.20",
                    port = 21,
                ),
            )
        val discovered =
            DiscoveredServerItem(
                protocol = ServerProtocol.FTP,
                name = "FTP Secondary",
                host = "192.168.1.20",
                port = 2121,
            )

        val match = findSavedServer(saved, discovered)
        assertNull(match)
    }

    @Test
    fun findSavedServerReturnsNullWhenProtocolDiffers() {
        val saved =
            listOf(
                SavedServer(
                    id = "server-sftp",
                    name = "Shared Box",
                    protocol = ServerProtocol.SFTP,
                    host = "192.168.1.10",
                    port = 22,
                ),
            )
        val discovered =
            DiscoveredServerItem(
                protocol = ServerProtocol.FTP,
                name = "Shared Box",
                host = "192.168.1.10",
                port = 22,
            )

        val match = findSavedServer(saved, discovered)
        assertNull(match)
    }

    @Test
    fun findSavedServerReturnsNullWhenOnlyNameMatches() {
        val saved =
            listOf(
                SavedServer(
                    id = "server-webdav",
                    name = "Nextcloud",
                    protocol = ServerProtocol.WEBDAV,
                    host = "cloud.lan",
                    port = 8080,
                ),
            )
        val discovered =
            DiscoveredServerItem(
                protocol = ServerProtocol.WEBDAV,
                name = "Nextcloud",
                host = "other.lan",
                port = 8080,
            )

        val match = findSavedServer(saved, discovered)
        assertNull(match)
    }
}
