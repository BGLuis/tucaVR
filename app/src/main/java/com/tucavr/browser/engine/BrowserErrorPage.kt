package com.tucavr.browser.engine

import androidx.annotation.StringRes
import com.tucavr.R
import com.tucavr.designsystem.VoidTheme
import org.mozilla.geckoview.WebRequestError

enum class BrowserErrorKind(
    @StringRes val descriptionRes: Int,
) {
    UNKNOWN_HOST(R.string.webbrowser_error_unknown_host),
    CONNECTION_REFUSED(R.string.webbrowser_error_connection_refused),
    TIMEOUT(R.string.webbrowser_error_timeout),
    OFFLINE(R.string.webbrowser_error_offline),
    CERTIFICATE(R.string.webbrowser_error_certificate),
    MALFORMED_URL(R.string.webbrowser_error_malformed_url),
    GENERIC(R.string.webbrowser_error_generic),
}

object BrowserErrorPage {
    fun kindOf(
        category: Int,
        code: Int,
    ): BrowserErrorKind =
        when (code) {
            WebRequestError.ERROR_UNKNOWN_HOST -> BrowserErrorKind.UNKNOWN_HOST
            WebRequestError.ERROR_CONNECTION_REFUSED -> BrowserErrorKind.CONNECTION_REFUSED
            WebRequestError.ERROR_NET_TIMEOUT -> BrowserErrorKind.TIMEOUT
            WebRequestError.ERROR_OFFLINE -> BrowserErrorKind.OFFLINE
            WebRequestError.ERROR_SECURITY_SSL,
            WebRequestError.ERROR_SECURITY_BAD_CERT,
            -> BrowserErrorKind.CERTIFICATE
            WebRequestError.ERROR_MALFORMED_URI,
            WebRequestError.ERROR_UNKNOWN_PROTOCOL,
            -> BrowserErrorKind.MALFORMED_URL
            else ->
                if (category == WebRequestError.ERROR_CATEGORY_SECURITY) {
                    BrowserErrorKind.CERTIFICATE
                } else {
                    BrowserErrorKind.GENERIC
                }
        }

    fun html(
        failedUrl: String?,
        title: String,
        description: String,
        urlLabel: String,
        retryLabel: String,
    ): String {
        val shownUrl = failedUrl.orEmpty()
        val retryLink =
            if (shownUrl.startsWith("http://") || shownUrl.startsWith("https://")) {
                """<a class="retry" href="${escape(shownUrl)}">${escape(retryLabel)}</a>"""
            } else {
                ""
            }
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${escape(title)}</title>
            <style>
            body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
            background:${hex(VoidTheme.colorBackground)};color:${hex(VoidTheme.colorText)};font-family:sans-serif}
            .card{max-width:640px;margin:16px;padding:32px;background:${hex(VoidTheme.colorSurface)};
            border:2px solid ${hex(VoidTheme.colorBorder)};border-radius:12px}
            h1{margin:0 0 12px;font-size:28px}
            p{margin:0 0 20px;font-size:20px;color:${hex(VoidTheme.colorTextSecondary)}}
            .url{margin-bottom:24px;font-family:monospace;font-size:16px;word-break:break-all;
            color:${hex(VoidTheme.colorTextSecondary)}}
            a.retry{display:inline-block;padding:14px 28px;border-radius:12px;font-size:20px;text-decoration:none;
            background:${hex(VoidTheme.colorAccent)};color:${hex(VoidTheme.colorText)}}
            </style>
            </head>
            <body>
            <div class="card">
            <h1>${escape(title)}</h1>
            <p>${escape(description)}</p>
            <div class="url">${escape(urlLabel)}: ${escape(shownUrl)}</div>
            $retryLink
            </div>
            </body>
            </html>
            """.trimIndent()
    }

    internal fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun hex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)
}
