package com.tucavr.network

import android.content.Context

/**
 * T7.3: historico de URLs HTTP(S) tocadas recentemente. Armazenamento
 * simples via `SharedPreferences` comuns — NAO criptografado, diferente das
 * credenciais SMB (T6.4). Uma URL de video nao e um segredo por padrao; se o
 * usuario colar uma URL com token de autenticacao embutido na query string,
 * isso ficaria em texto plano — limitacao conhecida, nao tratada nesta
 * sessao (o doc so exige criptografia explicitamente para credenciais SMB).
 */
class UrlHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("url_history", Context.MODE_PRIVATE)

    fun list(): List<String> =
        (prefs.getString(KEY, null) ?: "").split("\n").filter { it.isNotBlank() }

    fun add(url: String) {
        val updated = (listOf(url) + list().filterNot { it == url }).take(MAX_ENTRIES)
        prefs.edit().putString(KEY, updated.joinToString("\n")).apply()
    }

    companion object {
        private const val KEY = "recent_urls"
        private const val MAX_ENTRIES = 10
    }
}
