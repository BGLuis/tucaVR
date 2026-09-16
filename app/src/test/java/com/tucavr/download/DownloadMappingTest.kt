package com.tucavr.download

import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpServer
import com.tucavr.network.SavedServer
import com.tucavr.network.ServerProtocol
import com.tucavr.network.SftpServer
import com.tucavr.network.SmbServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes unitários de mapeamento de fontes de reprodução para URIs internas do motor de download.
 * Valida a codificação correta dos separadores NUL (\u0000) e esquemas de rede.
 */
class DownloadMappingTest {

    private val sep = '\u0000'

    @Test
    fun `http source mapping`() {
        val src = PlaybackSource.Http("https://example.com/video.mp4")
        assertEquals("https://example.com/video.mp4", src.toDownloadInternalUri())
        assertEquals("HTTP", src.toSourceTypeString())
    }

    @Test
    fun `smb source mapping with null separators`() {
        val server = SmbServer(
            id = "s1",
            name = "NAS",
            host = "192.168.1.100",
            port = 445,
            share = "Movies",
            username = "admin",
            password = "secret",
            domain = "WORKGROUP"
        )
        val src = PlaybackSource.Smb(server, "/Action/avatar.mkv", 5000L)
        val expected = "smb://192.168.1.100:445${sep}Movies${sep}Action/avatar.mkv${sep}admin${sep}secret${sep}WORKGROUP"

        assertEquals(expected, src.toDownloadInternalUri())
        assertEquals("SMB", src.toSourceTypeString())
    }

    @Test
    fun `ftp source mapping with null separators`() {
        val server = FtpServer(
            id = "f1",
            name = "FTP",
            host = "10.0.0.5",
            port = 21,
            username = "ftpuser",
            password = "ftppassword"
        )
        val src = PlaybackSource.Ftp(server, "vids/clip.mp4", 1234L)
        val expected = "ftp://10.0.0.5:21${sep}vids/clip.mp4${sep}ftpuser${sep}ftppassword"

        assertEquals(expected, src.toDownloadInternalUri())
        assertEquals("FTP", src.toSourceTypeString())
    }

    @Test
    fun `sftp source mapping with key`() {
        val server = SftpServer(
            id = "sf1",
            name = "SFTP",
            host = "10.0.0.6",
            port = 22,
            username = "sshuser",
            password = "",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----..."
        )
        val src = PlaybackSource.Sftp(server, "/home/sshuser/video.mp4", 4321L)
        val expected = "sftp://10.0.0.6:22${sep}home/sshuser/video.mp4${sep}sshuser${sep}${sep}-----BEGIN OPENSSH PRIVATE KEY-----..."

        assertEquals(expected, src.toDownloadInternalUri())
        assertEquals("SFTP", src.toSourceTypeString())
    }

    @Test
    fun `nfs source mapping includes version 3`() {
        val server = SavedServer(
            id = "nfs1",
            name = "NFS Server",
            protocol = ServerProtocol.NFS,
            host = "192.168.1.200",
            port = 2049,
            path = "/exports/media"
        )
        val src = PlaybackSource.Nfs(server, "movies/inception.mkv", 8888L)
        val expected = "nfs://192.168.1.200:2049${sep}/exports/media${sep}movies/inception.mkv${sep}3"

        assertEquals(expected, src.toDownloadInternalUri())
        assertEquals("NFS", src.toSourceTypeString())
    }

    @Test
    fun `webdav source mapping parses extraJson flags`() {
        val server = SavedServer(
            id = "wd1",
            name = "Nextcloud",
            protocol = ServerProtocol.WEBDAV,
            host = "cloud.example.com",
            port = 443,
            path = "/remote.php/webdav",
            username = "clouduser",
            extraJson = "{\"useHttps\":true,\"acceptInvalidCerts\":false}"
        )
        val src = PlaybackSource.Webdav(server, "Photos/trip.mp4", 9999L)
        val uri = src.toDownloadInternalUri()

        assertTrue(uri.startsWith("webdav://cloud.example.com:443${sep}/remote.php/webdav${sep}Photos/trip.mp4${sep}clouduser${sep}"))
        assertTrue(uri.endsWith("${sep}1${sep}0"))
        assertEquals("WEBDAV", src.toSourceTypeString())
    }

    @Test
    fun `dlna source mapping`() {
        val server = SavedServer(
            id = "dlna1",
            name = "Media Server",
            protocol = ServerProtocol.DLNA,
            host = "192.168.1.50",
            port = 50001
        )
        val src = PlaybackSource.Dlna(server, "Stream Title", "http://192.168.1.50:50001/stream.mp4", 1000L)
        assertEquals("http://192.168.1.50:50001/stream.mp4", src.toDownloadInternalUri())
        assertEquals("DLNA", src.toSourceTypeString())
    }

    @Test
    fun `local file source mapping`() {
        val src = PlaybackSource.LocalFile("/sdcard/Movies/clip.mp4", 2000L)
        assertEquals("/sdcard/Movies/clip.mp4", src.toDownloadInternalUri())
        assertEquals("LOCAL", src.toSourceTypeString())
    }
}
