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
        // Mantido para backward-compat; o modo granular usa SPATIAL_AUDIO_MODE.
        SPATIAL_AUDIO("spatial_audio", defaultEnabled = true),

        // Fase 0.3 Seção 3/4: Rastreamento de cabeça (Head Tracking) no áudio espacial.
        SPATIAL_HEAD_TRACKING("spatial_head_tracking", defaultEnabled = true),

        // Fase 0.3 T4.4: Speakers fixos relativos à tela (screen-locked) em vez do mundo.
        // Correto para conteúdo 2D — visível na UI apenas quando head tracking estiver ativo.
        SPATIAL_SCREEN_LOCKED("spatial_screen_locked", defaultEnabled = false),

        // Fase 0.2 T9: Carregamento automático de legendas (.srt / .vtt)
        AUTO_LOAD_SUBTITLES("auto_load_subtitles", defaultEnabled = true),

        // Painel de Estatísticas Técnicas / Stats for Nerds (docs/reports/DEBUG-STATS-MODAL.md)
        DEBUG_STATS_PANEL("debug_stats_panel", defaultEnabled = false),

        // Telemetria de Debug: exporta série temporal de reprodução em CSV (N2).
        DEBUG_STATS_EXPORT("debug_stats_export", defaultEnabled = false),

        // Pausar ao sair: pausa a reprodução ao sair pro menu do sistema ou passthrough
        PAUSE_ON_EXIT("pause_on_exit", defaultEnabled = true),
    }

    /** Chave usada para persistir o modo de áudio espacial como Int (0/1/2). */
    private const val KEY_SPATIAL_AUDIO_MODE = "spatial_audio_mode"

    fun isEnabled(context: Context, flag: Flag): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(flag.key, flag.defaultEnabled)

    fun setEnabled(context: Context, flag: Flag, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(flag.key, enabled).apply()
    }

    /**
     * Lê o modo de áudio espacial persistido (0 = DirectStereo, 1 = VirtualizedBinaural,
     * 2 = SimpleDownmix). O valor padrão (1) corresponde ao comportamento histórico do
     * toggle booleano `SPATIAL_AUDIO` ligado.
     */
    fun getSpatialAudioMode(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SPATIAL_AUDIO_MODE, 1)

    /** Persiste o modo de áudio espacial como Int (0/1/2). */
    fun setSpatialAudioMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SPATIAL_AUDIO_MODE, mode).apply()
    }
}
