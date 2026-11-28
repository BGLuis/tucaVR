package com.tucavr.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The inputs are the exact strings `connect_nodelay` (`rust/protocols/src/sftp/mod.rs`) returned
 * against the `sftp-test` container in `docker/network-tests/` (first connection, then the
 * container recreated with a new host key),
 * so a change to that format fails here instead of silently skipping the
 * trust prompt (#223).
 */
class SftpHostKeyStatusTest {
    private val fp = "SHA256:9dQnTSXZf+GVBocH6vN9Jl8uSqk8EmyRzYlqId6rVLE"
    private val other = "SHA256:euilPQ6S0v7hH83tKpTAt+d+pLfbziFFY62NroAnAE4"

    @Test
    fun unknownKeyAsksForConfirmationWithFingerprintAndKeyType() {
        val status =
            SftpHostKeyStatus.parse("ERROR:HOST_KEY_CONFIRM:$fp type=ssh-ed25519 needs confirmation: $fp")

        assertEquals(SftpHostKeyStatus.NeedsConfirmation(fp, "ssh-ed25519"), status)
    }

    @Test
    fun confirmationWithoutKeyTypeKeepsTheFingerprint() {
        val status = SftpHostKeyStatus.parse("ERROR:HOST_KEY_CONFIRM:$fp needs confirmation: $fp")

        assertEquals(SftpHostKeyStatus.NeedsConfirmation(fp, null), status)
    }

    @Test
    fun changedKeyIsDistinctFromConfirmationAndKeepsBothFingerprints() {
        val status =
            SftpHostKeyStatus.parse(
                "ERROR:HOST_KEY_CHANGED:expected $other, server presented $fp server identity changed: " +
                    "expected fingerprint $other, server presented $fp",
            )

        assertEquals(SftpHostKeyStatus.IdentityChanged(expected = other, presented = fp), status)
    }

    @Test
    fun confirmationWithoutFingerprintIsNotTrusted() {
        assertNull(SftpHostKeyStatus.parse("ERROR:HOST_KEY_CONFIRM: needs confirmation: "))
    }

    @Test
    fun listingsAndOtherErrorsAreNotHostKeyResults() {
        assertNull(SftpHostKeyStatus.parse("movie.mkv\t0\t1024\t0"))
        assertNull(SftpHostKeyStatus.parse("ERROR:autenticacao SFTP falhou (usuario/senha ou chave privada invalidos)"))
        assertNull(SftpHostKeyStatus.parse(""))
    }
}
