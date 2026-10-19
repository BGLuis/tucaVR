package com.tucavr.network

import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that server passwords and SFTP private keys are never exposed in toString(),
 * complying with CWE-532 / OWASP MASVS-STORAGE-2 and Issue #78.
 */
class ServerCredentialRedactionTest {
    @Test
    fun smbServerToStringRedactsPasswordWhenPresent() {
        val server =
            SmbServer(
                id = "smb-1",
                name = "NAS Casa",
                host = "192.168.1.100",
                port = 445,
                share = "videos",
                username = "luis",
                password = "super_secret_smb_password",
                domain = "WORKGROUP",
            )

        val str = server.toString()

        assertFalse("toString must never contain the plain text password", str.contains("super_secret_smb_password"))
        assertTrue("toString should contain the redaction mask", str.contains("password=[REDACTED]"))
        assertTrue("toString must retain non-sensitive fields", str.contains("id=smb-1"))
        assertTrue("toString must retain non-sensitive fields", str.contains("name=NAS Casa"))
        assertTrue("toString must retain non-sensitive fields", str.contains("host=192.168.1.100"))
        assertTrue("toString must retain non-sensitive fields", str.contains("share=videos"))
        assertTrue("toString must retain non-sensitive fields", str.contains("username=luis"))
        assertTrue("toString must retain non-sensitive fields", str.contains("domain=WORKGROUP"))
    }

    @Test
    fun smbServerToStringPreservesEmptyPasswordWithoutMask() {
        val guest =
            SmbServer(
                id = "smb-guest",
                name = "Guest NAS",
                host = "192.168.1.50",
                port = 445,
                share = "public",
                username = "",
                password = "",
                domain = "",
            )

        val str = guest.toString()

        assertFalse(str.contains("[REDACTED]"))
        assertTrue(str.contains("password=, domain="))
    }

    @Test
    fun ftpServerToStringRedactsPasswordWhenPresent() {
        val server =
            FtpServer(
                id = "ftp-1",
                name = "Seedbox FTP",
                host = "ftp.example.com",
                port = 21,
                username = "admin",
                password = "ultra_secret_ftp_pass",
            )

        val str = server.toString()

        assertFalse("toString must never contain the plain text password", str.contains("ultra_secret_ftp_pass"))
        assertTrue("toString should contain the redaction mask", str.contains("password=[REDACTED]"))
        assertTrue("toString must retain non-sensitive fields", str.contains("id=ftp-1"))
        assertTrue("toString must retain non-sensitive fields", str.contains("host=ftp.example.com"))
        assertTrue("toString must retain non-sensitive fields", str.contains("username=admin"))
    }

    @Test
    fun ftpServerToStringPreservesEmptyPasswordWithoutMask() {
        val anon =
            FtpServer(
                id = "ftp-anon",
                name = "Anon FTP",
                host = "ftp.example.com",
                port = 21,
                username = "anonymous",
                password = "",
            )

        val str = anon.toString()

        assertFalse(str.contains("[REDACTED]"))
        assertTrue(str.contains("password=)"))
    }

    @Test
    fun sftpServerToStringRedactsPasswordAndPrivateKeyWhenPresent() {
        val privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nMOCK_KEY_DATA_12345\n-----END OPENSSH PRIVATE KEY-----"
        val server =
            SftpServer(
                id = "sftp-1",
                name = "Remote SSH",
                host = "ssh.example.com",
                port = 2222,
                username = "root",
                password = "super_secret_ssh_password",
                privateKey = privateKeyPem,
            )

        val str = server.toString()

        assertFalse("toString must never contain the plain text password", str.contains("super_secret_ssh_password"))
        assertFalse("toString must never contain the private key data", str.contains("MOCK_KEY_DATA_12345"))
        assertFalse("toString must never contain PEM headers", str.contains("BEGIN OPENSSH"))
        assertTrue("toString should mask password", str.contains("password=[REDACTED]"))
        assertTrue("toString should mask privateKey", str.contains("privateKey=[REDACTED]"))
        assertTrue("toString must retain non-sensitive fields", str.contains("id=sftp-1"))
        assertTrue("toString must retain non-sensitive fields", str.contains("host=ssh.example.com"))
    }

    @Test
    fun sftpServerToStringWithKeyAuthOnly() {
        val privateKeyPem = "-----BEGIN RSA PRIVATE KEY-----\nRSA_SECRET_KEY\n-----END RSA PRIVATE KEY-----"
        val server =
            SftpServer(
                id = "sftp-key",
                name = "SSH Key Only",
                host = "ssh.example.com",
                port = 22,
                username = "deploy",
                password = "",
                privateKey = privateKeyPem,
            )

        val str = server.toString()

        assertFalse(str.contains("password=[REDACTED]"))
        assertTrue(str.contains("password=, privateKey=[REDACTED]"))
        assertFalse(str.contains("RSA_SECRET_KEY"))
    }

    @Test
    fun sftpServerToStringWithPasswordAuthOnly() {
        val server =
            SftpServer(
                id = "sftp-pass",
                name = "SSH Pass Only",
                host = "ssh.example.com",
                port = 22,
                username = "deploy",
                password = "secret_password",
                privateKey = null,
            )

        val str = server.toString()

        assertTrue(str.contains("password=[REDACTED]"))
        assertTrue(str.contains("privateKey=null"))
        assertFalse(str.contains("privateKey=[REDACTED]"))
        assertFalse(str.contains("secret_password"))
    }

    @Test
    fun nestedPlaybackSourceToStringDoesNotLeakSecrets() {
        val secret = "nested_secret_123"
        val pem = "-----BEGIN PRIVATE KEY-----\nSFTP_SECRET_PEM\n-----END PRIVATE KEY-----"

        val smbSource =
            PlaybackSource.Smb(
                server = SmbServer("s1", "SMB", "1.1.1.1", 445, "share", "u", secret, "d"),
                path = "movie.mp4",
            )
        val ftpSource =
            PlaybackSource.Ftp(
                server = FtpServer("f1", "FTP", "2.2.2.2", 21, "u", secret),
                path = "movie.mp4",
            )
        val sftpSource =
            PlaybackSource.Sftp(
                server = SftpServer("sf1", "SFTP", "3.3.3.3", 22, "u", secret, pem),
                path = "movie.mp4",
            )

        val smbStr = smbSource.toString()
        val ftpStr = ftpSource.toString()
        val sftpStr = sftpSource.toString()

        assertFalse("PlaybackSource.Smb must not leak secret", smbStr.contains(secret))
        assertFalse("PlaybackSource.Ftp must not leak secret", ftpStr.contains(secret))
        assertFalse("PlaybackSource.Sftp must not leak secret", sftpStr.contains(secret))
        assertFalse("PlaybackSource.Sftp must not leak private key", sftpStr.contains("SFTP_SECRET_PEM"))

        assertTrue(smbStr.contains("password=[REDACTED]"))
        assertTrue(ftpStr.contains("password=[REDACTED]"))
        assertTrue(sftpStr.contains("password=[REDACTED]"))
        assertTrue(sftpStr.contains("privateKey=[REDACTED]"))
    }

    @Test
    fun nestedDestinationToStringDoesNotLeakSecrets() {
        val secret = "destination_secret_456"
        val pem = "-----BEGIN PRIVATE KEY-----\nDEST_SECRET_PEM\n-----END PRIVATE KEY-----"

        val smbDest =
            Destination.NetworkFiles(
                server = SmbServer("s1", "SMB", "1.1.1.1", 445, "share", "u", secret, "d"),
                path = "folder",
            )
        val ftpDest =
            Destination.NetworkFtpFiles(
                server = FtpServer("f1", "FTP", "2.2.2.2", 21, "u", secret),
                path = "folder",
            )
        val sftpDest =
            Destination.NetworkSftpFiles(
                server = SftpServer("sf1", "SFTP", "3.3.3.3", 22, "u", secret, pem),
                path = "folder",
            )

        assertFalse(smbDest.toString().contains(secret))
        assertFalse(ftpDest.toString().contains(secret))
        assertFalse(sftpDest.toString().contains(secret))
        assertFalse(sftpDest.toString().contains("DEST_SECRET_PEM"))
    }

    @Test
    fun dataClassEqualityAndCopyPreserveActualCredentials() {
        val original =
            SmbServer(
                id = "smb-1",
                name = "NAS",
                host = "1.2.3.4",
                port = 445,
                share = "sh",
                username = "u",
                password = "real_password",
                domain = "d",
            )

        val identical = original.copy()
        assertEquals("Data class equals must compare actual properties", original, identical)
        assertEquals("Data class hashCode must match for identical instances", original.hashCode(), identical.hashCode())

        val differentPassword = original.copy(password = "different_password")
        assertNotEquals("Instances with different passwords must not be equal", original, differentPassword)

        // Verifies component destructuring preserves real credential
        val (_, _, _, _, _, _, pass, _) = original
        assertEquals("Destructured password must be the real secret", "real_password", pass)
    }
}
