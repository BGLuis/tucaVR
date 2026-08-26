package com.tucavr

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

        // Fase 0.4 T5: Foveated Rendering fixo (XR_FB_foveation, so caminho
        // Vulkan — ver vr_player_app_vulkan.cpp::ApplyFoveation). Desligado
        // por padrao: nunca validado em headset real ate agora.
        FOVEATED_RENDERING("foveated_rendering", defaultEnabled = false),

        // Fase 0.3 Seção 3/4: Áudio Espacial 3D (HRTF Binaural para 5.1/7.1 e Ambisonics).
        SPATIAL_AUDIO("spatial_audio", defaultEnabled = true),

        // Fase 0.3 Seção 3/4: Rastreamento de cabeça (Head Tracking) no áudio espacial.
        SPATIAL_HEAD_TRACKING("spatial_head_tracking", defaultEnabled = true),

        // Fase 0.2 T9: Carregamento automático de legendas (.srt / .vtt)
        AUTO_LOAD_SUBTITLES("auto_load_subtitles", defaultEnabled = true),

        // Painel de Estatísticas Técnicas / Stats for Nerds (docs/reports/DEBUG-STATS-MODAL.md)
        DEBUG_STATS_PANEL("debug_stats_panel", defaultEnabled = false),
    }

    fun isEnabled(context: Context, flag: Flag): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(flag.key, flag.defaultEnabled)

    fun setEnabled(context: Context, flag: Flag, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(flag.key, enabled).apply()
    }
}
