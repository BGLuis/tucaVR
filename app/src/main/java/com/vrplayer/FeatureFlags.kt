package com.vrplayer

import android.content.Context

/**
 * Feature flags simples via `SharedPreferences` (mesmo padrao de
 * `UrlHistoryStore`/credential stores). Por enquanto so serve pra desligar
 * features incompletas/problematicas direto no codigo (default de cada
 * `Flag`); a leitura/escrita ja fica pronta pra virar uma tela de
 * configuracoes futuramente sem mudar o storage.
 */
object FeatureFlags {
    private const val PREFS_NAME = "feature_flags"

    enum class Flag(val key: String, val defaultEnabled: Boolean) {
        // Desligado por padrao: causa travamentos/queda de performance
        // perceptiveis ao arrastar o seekbar (ver VRControlsPresentation).
        SCRUB_PREVIEW("scrub_preview", defaultEnabled = false),
    }

    fun isEnabled(context: Context, flag: Flag): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(flag.key, flag.defaultEnabled)

    fun setEnabled(context: Context, flag: Flag, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(flag.key, enabled).apply()
    }
}
