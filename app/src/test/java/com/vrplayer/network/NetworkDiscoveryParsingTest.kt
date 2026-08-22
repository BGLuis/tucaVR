package com.vrplayer.network

import com.vrplayer.screens.DiscoveredServerItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiscoveryParsingTest {

    private fun parseScanResults(raw: String): List<DiscoveredServerItem> {
        if (raw.isBlank() || raw.startsWith("ERROR:")) {
            return emptyList()
        }

        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\t")
            val protoStr = parts.getOrNull(0)?.uppercase() ?: return@mapNotNull null
            val proto = try {
                ServerProtocol.valueOf(protoStr)
            } catch (e: Exception) {
                return@mapNotNull null
            }
            val name = parts.getOrNull(1) ?: "Server"
            val host = parts.getOrNull(2) ?: return@mapNotNull null
            val port = parts.getOrNull(3)?.toIntOrNull() ?: 0
            val path = parts.getOrNull(4) ?: ""

            DiscoveredServerItem(
                protocol = proto,
                name = name,
                host = host,
                port = port,
                path = path
            )
        }
    }

    @Test
    fun parseMultipleProtocols() {
        val raw = """
            SMB	Synology NAS	192.168.1.50	445	
            NFS	Linux Media	192.168.1.60	2049	/volume1/media
            FTP	Seedbox	192.168.1.70	21	
            SFTP	Backup Server	192.168.1.80	22	
            DLNA	Plex Media Server	192.168.1.90	32400	http://192.168.1.90:32400/description.xml
        """.trimIndent()

        val results = parseScanResults(raw)
        assertEquals(5, results.size)

        assertEquals(ServerProtocol.SMB, results[0].protocol)
        assertEquals("Synology NAS", results[0].name)
        assertEquals("192.168.1.50", results[0].host)
        assertEquals(445, results[0].port)
        assertEquals("", results[0].path)

        assertEquals(ServerProtocol.NFS, results[1].protocol)
        assertEquals("Linux Media", results[1].name)
        assertEquals("192.168.1.60", results[1].host)
        assertEquals(2049, results[1].port)
        assertEquals("/volume1/media", results[1].path)

        assertEquals(ServerProtocol.DLNA, results[4].protocol)
        assertEquals("Plex Media Server", results[4].name)
        assertEquals("192.168.1.90", results[4].host)
        assertEquals(32400, results[4].port)
        assertEquals("http://192.168.1.90:32400/description.xml", results[4].path)
    }

    @Test
    fun parseErrorOrEmptyReturnsEmptyList() {
        assertTrue(parseScanResults("").isEmpty())
        assertTrue(parseScanResults("   \n\n  ").isEmpty())
        assertTrue(parseScanResults("ERROR:Socket timeout").isEmpty())
    }

    @Test
    fun parseUnknownProtocolIsIgnored() {
        val raw = "UNKNOWN_PROTO\tSome Server\t192.168.1.10\t80\t"
        assertTrue(parseScanResults(raw).isEmpty())
    }
}
