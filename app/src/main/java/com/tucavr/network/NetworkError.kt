package com.tucavr.network

import android.content.Context
import androidx.annotation.StringRes
import com.tucavr.R
import com.tucavr.debug.VRLog

/** Codes of `rust/protocols/src/error_code.rs`; the names must match its `ErrorCode::as_str`. */
enum class NetworkErrorCode(
    @StringRes val messageRes: Int,
) {
    AUTH(R.string.network_error_auth),
    TIMEOUT(R.string.network_error_timeout),
    NOT_FOUND(R.string.network_error_not_found),
    TLS(R.string.network_error_tls),
    UNREACHABLE(R.string.network_error_unreachable),
    HOST_KEY_CONFIRM(R.string.network_sftp_trust_title),
    HOST_KEY_CHANGED(R.string.network_sftp_host_key_changed),
    UNKNOWN(R.string.network_error_unknown),
}

/**
 * A failed native call, read from the bridge's `ERROR:<CODE>:<detail>` wire format (#59).
 * [detail] is the raw library text: it goes to the log, never to the screen.
 */
data class NetworkError(
    val code: NetworkErrorCode,
    val detail: String,
) {
    companion object {
        const val PREFIX = "ERROR:"

        /**
         * Returns null when [raw] is not an error. An error without a known code (older
         * native builds, or `ERROR:` built on the Kotlin side) becomes [NetworkErrorCode.UNKNOWN].
         */
        fun parse(raw: String): NetworkError? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            val body = trimmed.substring(PREFIX.length)
            val code = NetworkErrorCode.entries.firstOrNull { it.name == body.substringBefore(':') }
            // The detail is not trimmed: SftpHostKeyStatus reads an empty fingerprint from
            // `HOST_KEY_CONFIRM: ...` by its leading space.
            return if (code == null) {
                NetworkError(NetworkErrorCode.UNKNOWN, body)
            } else {
                NetworkError(code, body.substringAfter(':'))
            }
        }

        /** Translated message for [raw]; the raw detail is logged. [raw] must be an `ERROR:` result. */
        fun userMessage(
            context: Context,
            raw: String,
        ): String {
            val error = parse(raw) ?: NetworkError(NetworkErrorCode.UNKNOWN, raw)
            VRLog.w("Network error ${error.code}: ${error.detail}")
            return context.getString(error.code.messageRes)
        }
    }
}
