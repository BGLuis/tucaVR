package com.tucavr.network

/**
 * Host key outcome of an SFTP call, read from the `HOST_KEY_*` codes that
 * `connect_nodelay` in `rust/protocols/src/sftp/mod.rs` returns (#57 TOFU, #223).
 */
sealed class SftpHostKeyStatus {
    /** No fingerprint was pinned: the user must trust [fingerprint] before it is stored. */
    data class NeedsConfirmation(val fingerprint: String, val keyType: String?) : SftpHostKeyStatus()

    /** The server presented a key other than the pinned one (reinstalled server or MITM). */
    data class IdentityChanged(val expected: String?, val presented: String?) : SftpHostKeyStatus()

    companion object {
        private val TYPE_TOKEN = Regex("""\btype=(\S+)""")
        private val EXPECTED = Regex("""^expected (\S+?),""")
        private val PRESENTED = Regex("""server presented (\S+)""")

        /**
         * Parses a native SFTP result (see [NetworkError]). Returns null for listings and
         * for errors unrelated to the host key.
         */
        fun parse(result: String): SftpHostKeyStatus? {
            val error = NetworkError.parse(result) ?: return null
            val rest = error.detail
            return when (error.code) {
                NetworkErrorCode.HOST_KEY_CONFIRM -> {
                    val fingerprint = rest.substringBefore(' ').trim()
                    if (fingerprint.isEmpty()) {
                        null
                    } else {
                        NeedsConfirmation(fingerprint, TYPE_TOKEN.find(rest)?.groupValues?.get(1))
                    }
                }
                NetworkErrorCode.HOST_KEY_CHANGED ->
                    IdentityChanged(
                        expected = EXPECTED.find(rest)?.groupValues?.get(1),
                        presented = PRESENTED.find(rest)?.groupValues?.get(1),
                    )
                else -> null
            }
        }
    }
}
