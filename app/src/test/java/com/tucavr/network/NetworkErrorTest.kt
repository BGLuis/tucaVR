package com.tucavr.network

import com.tucavr.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class NetworkErrorTest {
    @Test
    fun codeAndDetailAreSplitAtTheFirstColonAfterTheCode() {
        val error = NetworkError.parse("ERROR:AUTH:Authentication failed: STATUS_LOGON_FAILURE")

        assertEquals(NetworkError(NetworkErrorCode.AUTH, "Authentication failed: STATUS_LOGON_FAILURE"), error)
    }

    @Test
    fun anErrorWithoutAKnownCodeIsUnknownAndKeepsTheWholeDetail() {
        assertEquals(
            NetworkError(NetworkErrorCode.UNKNOWN, "Connection timed out"),
            NetworkError.parse("ERROR:Connection timed out"),
        )
        assertEquals(NetworkError(NetworkErrorCode.UNKNOWN, "null"), NetworkError.parse("ERROR:null"))
    }

    @Test
    fun successfulResultsAreNotErrors() {
        assertNull(NetworkError.parse("movie.mkv\t0\t1024\t0"))
        assertNull(NetworkError.parse("OK\t1\t1024"))
    }

    @Test
    fun everyUserFacingCodeHasItsOwnMessage() {
        val userFacing = NetworkErrorCode.entries - NetworkErrorCode.HOST_KEY_CONFIRM
        assertEquals(userFacing.size, userFacing.map { it.messageRes }.toSet().size)
    }

    /** A code Rust sends that Kotlin does not know would silently show as UNKNOWN. */
    @Test
    fun codesMatchTheRustClassifier() {
        val rust = File(TestPaths.moduleDir, "../rust/protocols/src/error_code.rs").readText()
        val rustCodes = Regex("""Self::\w+ => "([A-Z_]+)"""").findAll(rust).map { it.groupValues[1] }.toSet()

        assertEquals(rustCodes, NetworkErrorCode.entries.map { it.name }.toSet())
    }
}
