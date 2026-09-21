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

        // Fase 0.3 Seção 2: Passthrough / Mixed Reality (XR_FB_passthrough, só
        // caminho Vulkan — ver vr_player_app_vulkan.cpp: SetupPassthrough).
        // Desligado por padrão: além de nunca validado em headset, o botão da
        // UI só habilita quando nativeIsPassthroughSupported() confirma a
        // extensão. Persistir aqui mantém o estado entre sessões.
        PASSTHROUGH("passthrough", defaultEnabled = false),

        // Fase 0.2 T9: Carregamento automático de legendas (.srt / .vtt)
        AUTO_LOAD_SUBTITLES("auto_load_subtitles", defaultEnabled = true),

        // Painel de Estatísticas Técnicas / Stats for Nerds (docs/reports/DEBUG-STATS-MODAL.md)
        DEBUG_STATS_PANEL("debug_stats_panel", defaultEnabled = false),

        // Telemetria de Debug: exporta série temporal de reprodução em CSV (N2).
        DEBUG_STATS_EXPORT("debug_stats_export", defaultEnabled = false),

        // Pausar ao sair: pausa a reprodução ao sair pro menu do sistema ou passthrough
        PAUSE_ON_EXIT("pause_on_exit", defaultEnabled = true),

        // Chroma Key: recorte de fundo (verde/azul) para vídeos 3D/2D em Passthrough
        CHROMA_KEY("chroma_key", defaultEnabled = false),

        // docs/reports/MODO-AMBIENTE.md: halo de luz atrás da tela derivado da cor
        // do frame (bias lighting), só ambiente Void, só caminho Vulkan — ver
        // vr_player_app_vulkan.cpp::shouldDrawAmbientHalo. Desligado por padrão:
        // nunca validado em headset real até agora.
        AMBIENT_MODE("ambient_mode", defaultEnabled = false),

        // RF-ENV-007: Modo Noturno (aquecimento ~3000K para redução de luz azul)
        NIGHT_MODE("night_mode", defaultEnabled = false),
    }

    /** Chave usada para persistir o brilho do ambiente 3D/Skybox (0.0f a 1.0f). Padrão: 1.0f (100%). */
    const val KEY_ENVIRONMENT_BRIGHTNESS = "environment_brightness"

    /** Chave usada para persistir a intensidade do Screen Glow (0=Off, 1=Subtle, 2=Strong). Padrão: 2. */
    const val KEY_SCREEN_GLOW_INTENSITY = "screen_glow_intensity"

    /** Chave usada para persistir a temperatura de cor em Kelvin (2700K a 6500K). Padrão: 6500.0f. */
    const val KEY_COLOR_TEMPERATURE = "color_temperature"

    const val SCREEN_GLOW_OFF = 0
    const val SCREEN_GLOW_SUBTLE = 1
    const val SCREEN_GLOW_STRONG = 2

    const val SCREEN_GLOW_INTENSITY_OFF = 0.0f
    const val SCREEN_GLOW_INTENSITY_SUBTLE = 0.45f
    const val SCREEN_GLOW_INTENSITY_STRONG = 0.85f

    fun screenGlowModeToFloat(mode: Int): Float = when (mode) {
        SCREEN_GLOW_SUBTLE -> SCREEN_GLOW_INTENSITY_SUBTLE
        SCREEN_GLOW_STRONG -> SCREEN_GLOW_INTENSITY_STRONG
        else -> SCREEN_GLOW_INTENSITY_OFF
    }

    /** Chave usada para persistir o modo de áudio espacial como Int (0/1/2). */
    private const val KEY_SPATIAL_AUDIO_MODE = "spatial_audio_mode"

    /** Chave usada para persistir o modo de Foveated Rendering como Int (0=Off, 1=Low, 2=Med, 3=High, 4=Auto). */
    private const val KEY_FOVEATED_RENDERING_MODE = "foveated_rendering_mode"

    /** Chave usada para persistir a opacidade do Passthrough (0.0f a 1.0f). */
    private const val KEY_PASSTHROUGH_OPACITY = "passthrough_opacity"

    /** Chave usada para persistir o modo de contorno (Edge Rendering) do Passthrough. */
    private const val KEY_PASSTHROUGH_EDGE_RENDERING = "passthrough_edge_rendering"

    /** Chave usada para persistir a cor chave do Chroma Key como Int (0xRRGGBB). Padrão: 0x00FF00 (verde). */
    private const val KEY_CHROMA_KEY_COLOR = "chroma_key_color"

    /** Chave usada para persistir a tolerância de corte do Chroma Key (0.01f a 1.0f). Padrão: 0.35f. */
    private const val KEY_CHROMA_KEY_SIMILARITY = "chroma_key_similarity"

    /** Chave usada para persistir a suavidade de borda do Chroma Key (0.001f a 0.5f). Padrão: 0.10f. */
    private const val KEY_CHROMA_KEY_SMOOTHNESS = "chroma_key_smoothness"

    /** Chave usada para persistir o modo de máscara do Passthrough (0=Off, 1=ChromaKey, 2=PackedAlpha). */
    private const val KEY_PASSTHROUGH_MASK_MODE = "passthrough_mask_mode"

    /** Chave usada para persistir o multiplicador de opacidade do Packed Alpha (1.0f a 2.5f). Padrão: 1.5f. */
    private const val KEY_PACKED_ALPHA_OPACITY_MULTIPLIER = "packed_alpha_opacity_multiplier"

    /** Chave usada para persistir o corte de fundo preto (Black Cutoff) do Packed Alpha (0.005f a 0.25f). Padrão: 0.06f (6%). */
    private const val KEY_PACKED_ALPHA_CUTOFF = "packed_alpha_cutoff"

    /** Chave usada para persistir o desbaste de borda (Matte Choke) do Packed Alpha (0 a 100%). Padrão: 65%. */
    private const val KEY_PACKED_ALPHA_CHOKE = "packed_alpha_choke"

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

    /**
     * Lê o modo de Foveated Rendering persistido (0 = Off, 1 = Low, 2 = Medium, 3 = High, 4 = Auto).
     */
    fun getFoveatedRenderingMode(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FOVEATED_RENDERING_MODE, 0)

    /** Persiste o modo de Foveated Rendering como Int (0 a 4) e sincroniza a flag legada. */
    fun setFoveatedRenderingMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FOVEATED_RENDERING_MODE, mode)
            .putBoolean(Flag.FOVEATED_RENDERING.key, mode != 0)
            .apply()
    }

    /** Lê a opacidade do Passthrough (0.0f a 1.0f). Padrão: 1.0f (100%). */
    fun getPassthroughOpacity(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_PASSTHROUGH_OPACITY, 1.0f)

    /** Persiste a opacidade do Passthrough. */
    fun setPassthroughOpacity(context: Context, opacity: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PASSTHROUGH_OPACITY, opacity.coerceIn(0.0f, 1.0f))
            .apply()
    }

    /** Lê se o Edge Rendering do Passthrough está ativo. Padrão: false. */
    fun getPassthroughEdgeRendering(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PASSTHROUGH_EDGE_RENDERING, false)

    /** Persiste o estado do Edge Rendering do Passthrough. */
    fun setPassthroughEdgeRendering(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PASSTHROUGH_EDGE_RENDERING, enabled)
            .apply()
    }

    /** Lê a cor do Chroma Key (RGB). Padrão: 0x00FF00 (verde). */
    fun getChromaKeyColor(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CHROMA_KEY_COLOR, 0x00FF00)

    /** Persiste a cor do Chroma Key. */
    fun setChromaKeyColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CHROMA_KEY_COLOR, color and 0x00FFFFFF)
            .apply()
    }

    /** Lê a tolerância de corte do Chroma Key (0.01f a 1.0f). Padrão: 0.35f (35%). */
    fun getChromaKeySimilarity(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_CHROMA_KEY_SIMILARITY, 0.35f)

    /** Persiste a tolerância de corte do Chroma Key. */
    fun setChromaKeySimilarity(context: Context, similarity: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CHROMA_KEY_SIMILARITY, similarity.coerceIn(0.01f, 1.0f))
            .apply()
    }

    /** Lê a suavidade de borda do Chroma Key (0.001f a 0.5f). Padrão: 0.10f (10%). */
    fun getChromaKeySmoothness(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_CHROMA_KEY_SMOOTHNESS, 0.10f)

    /** Persiste a suavidade de borda do Chroma Key. */
    fun setChromaKeySmoothness(context: Context, smoothness: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CHROMA_KEY_SMOOTHNESS, smoothness.coerceIn(0.001f, 0.5f))
            .apply()
    }

    /** Lê o modo de máscara do Passthrough (0=Off, 1=ChromaKey, 2=PackedAlpha). */
    fun getPassthroughMaskMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_PASSTHROUGH_MASK_MODE)) {
            return prefs.getInt(KEY_PASSTHROUGH_MASK_MODE, 0)
        }
        return if (isEnabled(context, Flag.CHROMA_KEY)) 1 else 0
    }

    /** Persiste o modo de máscara do Passthrough. */
    fun setPassthroughMaskMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PASSTHROUGH_MASK_MODE, mode)
            .putBoolean(Flag.CHROMA_KEY.key, mode != 0)
            .apply()
    }

    /** Lê o multiplicador de opacidade do Packed Alpha (1.0f a 2.5f). Padrão: 1.5f (HereSphere/DeoVR). */
    fun getPackedAlphaOpacityMultiplier(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_PACKED_ALPHA_OPACITY_MULTIPLIER, 1.5f)

    /** Persiste o multiplicador de opacidade do Packed Alpha. */
    fun setPackedAlphaOpacityMultiplier(context: Context, multiplier: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PACKED_ALPHA_OPACITY_MULTIPLIER, multiplier.coerceIn(1.0f, 2.5f))
            .apply()
    }

    /** Lê o corte de fundo preto (Black Cutoff) do Packed Alpha (0.005f a 0.25f). Padrão: 0.06f (6%). */
    fun getPackedAlphaCutoff(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_PACKED_ALPHA_CUTOFF, 0.06f)

    /** Persiste o corte de fundo preto do Packed Alpha. */
    fun setPackedAlphaCutoff(context: Context, cutoff: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PACKED_ALPHA_CUTOFF, cutoff.coerceIn(0.005f, 0.25f))
            .apply()
    }

    /** Lê o desbaste de borda (Matte Choke) do Packed Alpha (0 a 100%). Padrão: 65%. */
    fun getPackedAlphaChoke(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PACKED_ALPHA_CHOKE, 65)

    /** Persiste o desbaste de borda do Packed Alpha. */
    fun setPackedAlphaChoke(context: Context, choke: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PACKED_ALPHA_CHOKE, choke.coerceIn(0, 100))
            .apply()
    }

    /** Lê o brilho do ambiente virtual (0.0f a 1.0f). Padrão: 1.0f (100%). */
    fun getEnvironmentBrightness(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_ENVIRONMENT_BRIGHTNESS, 1.0f)

    /** Persiste o brilho do ambiente virtual. */
    fun setEnvironmentBrightness(context: Context, brightness: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_ENVIRONMENT_BRIGHTNESS, brightness.coerceIn(0.0f, 1.0f))
            .apply()
    }

    /** Lê o modo de intensidade do Screen Glow (0=Off, 1=Subtle, 2=Strong). Padrão: 2. */
    fun getScreenGlowIntensityMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_SCREEN_GLOW_INTENSITY)) {
            return prefs.getInt(KEY_SCREEN_GLOW_INTENSITY, SCREEN_GLOW_STRONG)
        }
        return if (isEnabled(context, Flag.AMBIENT_MODE)) SCREEN_GLOW_STRONG else SCREEN_GLOW_OFF
    }

    /** Persiste o modo de intensidade do Screen Glow e sincroniza com a flag AMBIENT_MODE. */
    fun setScreenGlowIntensityMode(context: Context, mode: Int) {
        val clamped = mode.coerceIn(SCREEN_GLOW_OFF, SCREEN_GLOW_STRONG)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SCREEN_GLOW_INTENSITY, clamped)
            .putBoolean(Flag.AMBIENT_MODE.key, clamped != SCREEN_GLOW_OFF)
            .apply()
    }

    /** Lê a intensidade float do Screen Glow (0.0f, 0.45f ou 0.85f). */
    fun getScreenGlowIntensityFloat(context: Context): Float =
        screenGlowModeToFloat(getScreenGlowIntensityMode(context))

    /** Lê a temperatura de cor em Kelvin (2700K a 6500K). Padrão: 6500K. */
    fun getColorTemperature(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_COLOR_TEMPERATURE, 6500.0f)

    /** Persiste a temperatura de cor em Kelvin. */
    fun setColorTemperature(context: Context, kelvin: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_COLOR_TEMPERATURE, kelvin.coerceIn(2700.0f, 6500.0f))
            .apply()
    }

    /** Lê se o Modo Noturno está ativo. Padrão: false. */
    fun isNightModeEnabled(context: Context): Boolean =
        isEnabled(context, Flag.NIGHT_MODE)

    /** Persiste o estado do Modo Noturno. */
    fun setNightModeEnabled(context: Context, enabled: Boolean) {
        setEnabled(context, Flag.NIGHT_MODE, enabled)
    }
}
