package com.tucavr.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedServerMappingTest {

    @Test
    fun smbServerToSavedServerMapping() {
        val smb = SmbServer(
            id = "smb-123",
            name = "NAS Casa",
            host = "192.168.1.100",
            port = 445,
            share = "videos",
            username = "luis",
            password = "secretpassword",
            domain = "WORKGROUP"
        )

        val saved = smb.toSavedServer()

        assertEquals("smb-123", saved.id)
        assertEquals("NAS Casa", saved.name)
        assertEquals(ServerProtocol.SMB, saved.protocol)
        assertEquals("192.168.1.100", saved.host)
        assertEquals(445, saved.port)
        assertEquals("videos", saved.path)
        assertEquals("luis", saved.username)
        assertEquals("WORKGROUP", saved.domain)
        assertFalse(saved.isGuest)

        val recovered = saved.toSmbServer(password = "recovered_pass")
        assertEquals(smb.id, recovered.id)
        assertEquals(smb.name, recovered.name)
        assertEquals(smb.host, recovered.host)
        assertEquals(smb.port, recovered.port)
        assertEquals(smb.share, recovered.share)
        assertEquals(smb.username, recovered.username)
        assertEquals("recovered_pass", recovered.password)
        assertEquals(smb.domain, recovered.domain)
    }

    @Test
    fun ftpServerToSavedServerMapping() {
        val ftp = FtpServer(
            id = "ftp-456",
            name = "Seedbox FTP",
            host = "ftp.example.com",
            port = 21,
            username = "anonymous",
            password = ""
        )

        val saved = ftp.toSavedServer()

        assertEquals("ftp-456", saved.id)
        assertEquals("Seedbox FTP", saved.name)
        assertEquals(ServerProtocol.FTP, saved.protocol)
        assertEquals("ftp.example.com", saved.host)
        assertEquals(21, saved.port)

        val recovered = saved.toFtpServer(password = "pass123")
        assertEquals(ftp.id, recovered.id)
        assertEquals(ftp.name, recovered.name)
        assertEquals(ftp.host, recovered.host)
        assertEquals(ftp.port, recovered.port)
        assertEquals("pass123", recovered.password)
    }

    @Test
    fun sftpServerToSavedServerMapping() {
        val sftp = SftpServer(
            id = "sftp-789",
            name = "Servidor Remoto SSH",
            host = "ssh.example.com",
            port = 2222,
            username = "root",
            password = "",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nMOCK\n-----END OPENSSH PRIVATE KEY-----"
        )

        val saved = sftp.toSavedServer()

        assertEquals("sftp-789", saved.id)
        assertEquals("Servidor Remoto SSH", saved.name)
        assertEquals(ServerProtocol.SFTP, saved.protocol)
        assertEquals("ssh.example.com", saved.host)
        assertEquals(2222, saved.port)
        assertEquals("root", saved.username)

        val recovered = saved.toSftpServer(password = "", privateKey = sftp.privateKey)
        assertEquals(sftp.id, recovered.id)
        assertEquals(sftp.name, recovered.name)
        assertEquals(sftp.host, recovered.host)
        assertEquals(sftp.port, recovered.port)
        assertEquals(sftp.privateKey, recovered.privateKey)
        assertTrue(recovered.usesKeyAuth)
    }

    @Test
    fun guestOrAnonymousCheck() {
        val guest = SavedServer(
            name = "Guest SMB",
            protocol = ServerProtocol.SMB,
            host = "10.0.0.1",
            port = 445,
            username = ""
        )
        assertTrue(guest.isGuest)

        val user = SavedServer(
            name = "User SMB",
            protocol = ServerProtocol.SMB,
            host = "10.0.0.1",
            port = 445,
            username = "admin"
        )
        assertFalse(user.isGuest)
    }
}
