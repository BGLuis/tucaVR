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
        private const val CONFIRM = "HOST_KEY_CONFIRM:"
        private const val CHANGED = "HOST_KEY_CHANGED:"
        private val TYPE_TOKEN = Regex("""\btype=(\S+)""")
        private val EXPECTED = Regex("""^expected (\S+?),""")
        private val PRESENTED = Regex("""server presented (\S+)""")

        /**
         * Parses a native SFTP result, with or without the `ERROR:` prefix
         * ([com.tucavr.filebrowser.NetworkDirectoryLoader] strips it). Returns null for
         * listings and for errors unrelated to the host key.
         */
        fun parse(result: String): SftpHostKeyStatus? {
            val message = result.trim().removePrefix("ERROR:").trim()
            return when {
                message.startsWith(CONFIRM) -> {
                    val rest = message.removePrefix(CONFIRM)
                    val fingerprint = rest.substringBefore(' ').trim()
                    if (fingerprint.isEmpty()) {
                        null
                    } else {
                        NeedsConfirmation(fingerprint, TYPE_TOKEN.find(rest)?.groupValues?.get(1))
                    }
                }
                message.startsWith(CHANGED) -> {
                    val rest = message.removePrefix(CHANGED)
                    IdentityChanged(
                        expected = EXPECTED.find(rest)?.groupValues?.get(1),
                        presented = PRESENTED.find(rest)?.groupValues?.get(1),
                    )
                }
                else -> null
            }
        }
    }
}
