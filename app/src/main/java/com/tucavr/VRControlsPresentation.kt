package com.tucavr

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.Display
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ScrubStrip
import com.tucavr.history.historyKey
import com.tucavr.navigation.PlaybackSource
import com.tucavr.screens.ScreenFormatCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class VRControlsPresentation(
    outerContext: Context,
    display: Display,
    // `Presentation.context` (herdado de Dialog) NAO e o `outerContext` que
    // passamos aqui embaixo — o Android cria por baixo dos panos um
    // ContextThemeWrapper em volta de um display-context derivado dele.
    // Um cast `context as? VRActivity` sempre falha (vira null) e todo
    // `?.nativeX(...)` correspondente vira um no-op silencioso. Por isso
    // guardamos a Activity de verdade explicitamente aqui, em vez de
    // depender de `context`.
    private val activity: VRActivity,
    private val onPlayPause: () -> Unit
) : Presentation(outerContext, display, android.R.style.Theme_NoTitleBar_Fullscreen) {

    fun updateTitle(title: String) {
        if (::titleLabel.isInitialized) {
            titleLabel.text = title
        }
    }

    /**
     * Reseta o estado visual do player flutuante (labels, progresso e modais)
     * para quando a reprodução for encerrada.
     */
    fun resetPlaybackState() {
        stopScrubPreview()
        if (::timeLabel.isInitialized) timeLabel.text = "00:00"
        if (::totalTimeLabel.isInitialized) totalTimeLabel.text = "00:00"
        if (::titleLabel.isInitialized) titleLabel.text = ""
        if (::seekBar.isInitialized) seekBar.progress = 0
        totalDuration = 0f
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.setImageResource(R.drawable.icon_play)
        }
        if (::loadingSpinner.isInitialized) {
            loadingSpinner.visibility = View.GONE
        }
    }


    private lateinit var seekBar: SeekBar
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryLabel: TextView
    private lateinit var thermalIcon: ImageView
    private lateinit var thermalLabel: TextView
    private lateinit var clockLabel: TextView
    private lateinit var btnPlayPause: com.tucavr.designsystem.VoidIconButton
    private var hudReceiver: android.content.BroadcastReceiver? = null
    private lateinit var timeLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var totalTimeLabel: TextView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var scrubPreview: ImageView
    private lateinit var btnDebugStats: VoidIconButton
    private var lastKnownBatteryPercent: Int = 100
    private var lastKnownIsCharging: Boolean = false
    private var isDragging = false
    private var lastSeekTime = 0L
    private var totalDuration = 0f

    // Preview de arrasto no seekbar (T-seek-ux). Escopo proprio (nunca
    // cancelado explicitamente) em vez de lifecycleScope porque
    // `Presentation` nao e um `LifecycleOwner` — mesmo padrao ja usado por
    // `PlaybackHistoryTracker` (escopo vive junto do processo).
    private val scrubScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var networkScrubStrip: ScrubStrip? = null
    private var localScrubRetriever: MediaMetadataRetriever? = null
    // "Latest wins": um `getFrameAtTime` local e uma decodificacao sincrona
    // (dezenas de ms) — sem isto, arrastar rapido enfileiraria um decode
    // por pixel de movimento, cada um mais atrasado que o anterior.
    private var localScrubJob: Job? = null
    // Job.cancel() nao interrompe a chamada JNI bloqueante em andamento (ver
    // stopScrubPreview) — guardada so pra evitar reatribuir networkScrubStrip
    // depois que o usuario ja soltou o dedo.
    private var networkScrubJob: Job? = null

    // Modos esféricos (360/180 e variantes estéreo) usam esfera, não quad —
    // o overlay de preview sobre o vídeo (nativeUpdateScrubOverlay) só cobre modo plano.
    private fun isSphereMode(mode: Int) = ScreenFormatCatalog.isSpherical(mode)

    // Resolucao pedida pro decode escalado de scrub local (ver updateScrubPreview) —
    // rapido o bastante pra acompanhar arrastos continuos, ainda reconhecivel.
    private companion object {
        const val LOCAL_SCRUB_WIDTH = 320
        const val LOCAL_SCRUB_HEIGHT = 180
    }

    private var lastKnownMode = 0

    fun updateProgress(currentSec: Float, totalSec: Float) {
        totalDuration = totalSec
        if (!isDragging && totalSec > 0 && (System.currentTimeMillis() - lastSeekTime > 800)) {
            seekBar.progress = ((currentSec / totalSec) * 100).toInt()
            timeLabel.text = formatTime(currentSec)
        }
        if (::totalTimeLabel.isInitialized) {
            totalTimeLabel.text = formatTime(totalSec)
        }

        // Mantém lastKnownMode atualizado com o modo nativo para que
        // a pré-visualização de scrub reconheça modos esféricos vs planos.
        lastKnownMode = activity.nativeGet3DMode()
    }

    /**
     * Notificado quando a Feature Flag do painel de estatísticas técnicas muda em tempo real.
     */
    fun onDebugStatsFlagChanged(enabled: Boolean) {
        if (::btnDebugStats.isInitialized) {
            btnDebugStats.visibility = if (enabled) View.VISIBLE else View.GONE
        }
    }

    /**
     * T14.3: Atualiza o ícone e rótulo de status térmico no header do painel de controles.
     */
    fun onThermalStateChanged(state: ThermalMonitor.ThermalState) {
        if (!::thermalIcon.isInitialized || !::thermalLabel.isInitialized) return
        when (state.level) {
            ThermalMonitor.ThermalLevel.NORMAL,
            ThermalMonitor.ThermalLevel.LIGHT -> {
                thermalIcon.visibility = View.GONE
                thermalLabel.visibility = View.GONE
            }
            ThermalMonitor.ThermalLevel.MODERATE -> {
                val color = android.graphics.Color.parseColor("#FFCC00")
                thermalIcon.visibility = View.VISIBLE
                thermalIcon.setColorFilter(color)
                thermalLabel.visibility = View.VISIBLE
                thermalLabel.text = context.getString(R.string.thermal_level_moderate)
                thermalLabel.setTextColor(color)
            }
            ThermalMonitor.ThermalLevel.SEVERE -> {
                val color = android.graphics.Color.parseColor("#FF8800")
                thermalIcon.visibility = View.VISIBLE
                thermalIcon.setColorFilter(color)
                thermalLabel.visibility = View.VISIBLE
                thermalLabel.text = context.getString(R.string.thermal_level_severe)
                thermalLabel.setTextColor(color)
            }
            ThermalMonitor.ThermalLevel.CRITICAL,
            ThermalMonitor.ThermalLevel.SHUTDOWN -> {
                val color = android.graphics.Color.parseColor("#FF3333")
                thermalIcon.visibility = View.VISIBLE
                thermalIcon.setColorFilter(color)
                thermalLabel.visibility = View.VISIBLE
                thermalLabel.text = context.getString(R.string.thermal_level_critical)
                thermalLabel.setTextColor(color)
            }
        }
    }

    /**
     * Sinal de loading (ver VRActivity.updateMediaState), chamado ~10x/s
     * pelo C++. Feedback de play/pause/seek agora e desenhado sobre a quad
     * de video (native/src/vr_player_feedback_overlay.h), nao mais aqui no
     * painel — este metodo so cuida do spinner de loading.
     */
    fun updateMediaState(isLoading: Boolean, isPlaying: Boolean) {
        loadingSpinner.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.setImageResource(if (isPlaying) R.drawable.icon_pause else R.drawable.icon_play)
        }
    }

    /**
     * Inicio do arrasto no seekbar (T-seek-ux): prepara a fonte de preview
     * conforme o tipo de video tocando. Rede (SMB/SFTP): busca a trilha ja
     * gerada/cacheada — se este for o primeiro arrasto deste video na
     * sessao, a trilha ainda nao existe e so fica pronta no MEIO do
     * arrasto (limitacao conhecida, ver stopScrubPreview); arrastos
     * seguintes no mesmo video sao instantaneos (cache em memoria). Local:
     * abre um `MediaMetadataRetriever` reaproveitado por todo o arrasto,
     * em vez de um por tick. FTP/HTTP(S): sem preview ainda (mesma
     * limitacao de geracao de thumbnail — ver NetworkThumbnailGenerator).
     */
    private fun startScrubPreview() {
        if (!FeatureFlags.isEnabled(context, FeatureFlags.Flag.SCRUB_PREVIEW)) return
        activity.nativeSetScrubOverlayVisible(true)
        when (val source = activity.currentPlaybackSource) {
            is PlaybackSource.Smb, is PlaybackSource.Sftp -> {
                networkScrubJob = scrubScope.launch {
                    networkScrubStrip = NetworkThumbnailGenerator.getScrubStrip(context, activity, source)
                }
            }
            is PlaybackSource.LocalFile -> {
                localScrubRetriever = MediaMetadataRetriever().apply {
                    runCatching { setDataSource(source.path) }
                }
            }
            else -> {}
        }
    }

    /**
     * Atualiza o preview pra `positionSeconds`. Em modo plano (2D/SBS/OU),
     * empurra o frame direto pro overlay sobre o quad do video em vez do
     * painel pequeno (ver isSphereMode/nativeUpdateScrubOverlay); em 360/180
     * (esfera, sem quad pra sobrepor) mantem o painel de sempre. Rede: so
     * leitura de memoria (recorta um frame ja decodificado, sem I/O) — roda
     * direto na UI thread. Local: `getFrameAtTime` e uma decodificacao
     * sincrona (dezenas de ms), roda em `Dispatchers.IO`; `localScrubJob`
     * garante que so a ULTIMA posicao pedida importa (arrastar rapido nao
     * enfileira um decode atrasado atras do outro).
     */
    private fun updateScrubPreview(positionSeconds: Float) {
        android.util.Log.i("VRPlayer_Scrub", "updateScrubPreview: pos=$positionSeconds networkScrubStrip=${networkScrubStrip != null} localScrubRetriever=${localScrubRetriever != null} mode=$lastKnownMode")
        networkScrubStrip?.let { strip ->
            if (isSphereMode(lastKnownMode)) {
                strip.bitmapAt(positionSeconds)?.let { bitmap ->
                    scrubPreview.visibility = View.VISIBLE
                    scrubPreview.setImageBitmap(bitmap)
                }
            } else {
                scrubPreview.visibility = View.GONE
                strip.rgbaAt(positionSeconds)?.let { rgba ->
                    activity.nativeUpdateScrubOverlay(rgba, strip.frameWidth, strip.frameHeight)
                }
            }
            return
        }
        val retriever = localScrubRetriever ?: return
        localScrubJob?.cancel()
        localScrubJob = scrubScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val timeUs = (positionSeconds * 1_000_000).toLong()
                    // getScaledFrameAtTime decodifica ja na resolucao pedida (o
                    // decoder pula trabalho, nao e um resize depois) — em 4K,
                    // decodificar o frame inteiro pra descartar a resolucao levava
                    // tempo suficiente (dezenas a centenas de ms) pra o preview
                    // ficar sempre atrasado dezenas de posicoes atras do arrasto
                    // real. API 27+; abaixo disso (nunca deve rodar no Quest) cai
                    // pro caminho antigo sem downscale.
                    if (android.os.Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(
                            timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, LOCAL_SCRUB_WIDTH, LOCAL_SCRUB_HEIGHT
                        )
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                }.getOrNull()
            }
            if (bitmap == null) return@launch
            if (isSphereMode(lastKnownMode)) {
                scrubPreview.visibility = View.VISIBLE
                scrubPreview.setImageBitmap(bitmap)
            } else {
                scrubPreview.visibility = View.GONE
                // getFrameAtTime nao garante o Config do Bitmap (pode vir RGB_565,
                // 2 bytes/pixel, em vez de ARGB_8888) — o overlay nativo espera
                // sempre RGBA8 (4 bytes/pixel), senao o upload e rejeitado por
                // tamanho incompativel.
                val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                    bitmap
                } else {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
                val buffer = ByteBuffer.allocate(argbBitmap.byteCount)
                argbBitmap.copyPixelsToBuffer(buffer)
                activity.nativeUpdateScrubOverlay(buffer.array(), argbBitmap.width, argbBitmap.height)
            }
        }
    }

    private fun stopScrubPreview() {
        activity.nativeSetScrubOverlayVisible(false)
        scrubPreview.visibility = View.GONE
        localScrubJob?.cancel()
        localScrubJob = null
        networkScrubJob?.cancel()
        networkScrubJob = null
        // Job.cancel() acima nao interrompe a chamada JNI bloqueante ja em
        // andamento (getScrubStrip -> nativeS*GenerateThumbnailStrip) — sem
        // isto, ela continua rodando e competindo por banda/decoder com o
        // playback principal por ate minutos num arquivo grande.
        activity.nativeCancelScrubStrip()
        networkScrubStrip = null
        localScrubRetriever?.release()
        localScrubRetriever = null
    }

    private fun formatTime(seconds: Float): String {
        val total = seconds.toInt().coerceAtLeast(0)
        // Formato numerico puro (MM:SS) -- nao depende de idioma, mantido via
        // String.format direto (nao e uma string de UI traduzivel).
        return String.format("%02d:%02d", total / 60, total % 60)
    }

    private fun speedFromProgress(progress: Int): Float = 0.5f + (progress / 100f) * 1.5f


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_TICK)
        }
        hudReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                if (intent.action == android.content.Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                    val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    
                    if (level >= 0 && scale > 0) {
                        val pct = (level * 100) / scale
                        lastKnownBatteryPercent = pct
                        lastKnownIsCharging = isCharging
                        if (::batteryLabel.isInitialized) batteryLabel.text = "${pct}%"
                        
                        val iconRes = when {
                            isCharging -> com.tucavr.R.drawable.icon_battery_charging
                            pct > 80 -> com.tucavr.R.drawable.icon_battery_full
                            pct > 40 -> com.tucavr.R.drawable.icon_battery_medium
                            pct > 15 -> com.tucavr.R.drawable.icon_battery_low
                            else -> com.tucavr.R.drawable.icon_battery_empty
                        }
                        if (::batteryIcon.isInitialized) batteryIcon.setImageResource(iconRes)
                        
                        val color = if (!isCharging && pct <= 15) android.graphics.Color.parseColor("#FF4444") else com.tucavr.designsystem.VoidTheme.colorText
                        if (::batteryIcon.isInitialized) batteryIcon.setColorFilter(color)
                        if (::batteryLabel.isInitialized) batteryLabel.setTextColor(color)
                    }
                } else if (intent.action == android.content.Intent.ACTION_TIME_TICK) {
                    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    if (::clockLabel.isInitialized) clockLabel.text = sdf.format(java.util.Date())
                }
            }
        }
        context.registerReceiver(hudReceiver, filter)
        
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        if (::clockLabel.isInitialized) clockLabel.text = sdf.format(java.util.Date())
        onThermalStateChanged(activity.thermalMonitor.currentState)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hudReceiver?.let { context.unregisterReceiver(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Fundo transparente
        }

        // --- Top Bar (Header) ---
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = VoidTheme.dpToPx(context, 10f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val btnClose = VoidIconButton(context, R.drawable.icon_x, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            setOnClickListener { activity.stopPlayback() }
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f))
        }
        headerRow.addView(btnClose)

        headerRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f) })

        val statusBadge = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(VoidTheme.colorBackground) // #121212
                cornerRadius = VoidTheme.dp(context, 18f)
            }
            val padH = VoidTheme.dpToPx(context, 16f)
            val padV = VoidTheme.dpToPx(context, 10f)
            setPadding(padH, padV, padH, padV)
        }

        batteryIcon = ImageView(context).apply {
            setImageResource(R.drawable.icon_battery_full)
            setColorFilter(VoidTheme.colorText)
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 36f), VoidTheme.dpToPx(context, 36f)).apply {
                rightMargin = VoidTheme.dpToPx(context, 10f)
            }
        }
        statusBadge.addView(batteryIcon)

        batteryLabel = TextView(context).apply {
            text = "100%"
            typeface = VoidTheme.typefaceMono
            textSize = 32f
            setTextColor(VoidTheme.colorText)
            setPadding(0, 0, VoidTheme.dpToPx(context, 20f), 0)
        }
        statusBadge.addView(batteryLabel)

        val clockIcon = ImageView(context).apply {
            setImageResource(R.drawable.icon_clock)
            setColorFilter(VoidTheme.colorText)
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 36f), VoidTheme.dpToPx(context, 36f)).apply {
                rightMargin = VoidTheme.dpToPx(context, 10f)
            }
        }
        statusBadge.addView(clockIcon)

        clockLabel = TextView(context).apply {
            text = "12:00"
            typeface = VoidTheme.typefaceMono
            textSize = 32f
            setTextColor(VoidTheme.colorText)
        }
        statusBadge.addView(clockLabel)

        thermalIcon = ImageView(context).apply {
            setImageResource(R.drawable.icon_thermal)
            setColorFilter(VoidTheme.colorText)
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 36f), VoidTheme.dpToPx(context, 36f)).apply {
                leftMargin = VoidTheme.dpToPx(context, 20f)
                rightMargin = VoidTheme.dpToPx(context, 10f)
            }
            visibility = View.GONE
        }
        statusBadge.addView(thermalIcon)

        thermalLabel = TextView(context).apply {
            text = ""
            typeface = VoidTheme.typefaceMono
            textSize = 32f
            setTextColor(VoidTheme.colorText)
            visibility = View.GONE
        }
        statusBadge.addView(thermalLabel)

        headerRow.addView(statusBadge)

        headerRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f) })

        val btnSettings = VoidIconButton(context, R.drawable.icon_settings, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f))
        }
        headerRow.addView(btnSettings)

        root.addView(headerRow)

        // Espaçador exato do Figma (10px)
        root.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, VoidTheme.dpToPx(context, 10f)) })

        // --- Bottom Bar (Controles) ---
        val bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(VoidTheme.colorBackground)
                cornerRadius = VoidTheme.dp(context, 18f)
            }
            val padH = VoidTheme.dpToPx(context, 61f)
            val padV = VoidTheme.dpToPx(context, 54f)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        titleLabel = TextView(context).apply {
            text = ""
            typeface = VoidTheme.typefaceBody
            textSize = 32f
            setTextColor(VoidTheme.colorText)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 32f))
        }
        bottomPanel.addView(titleLabel)

        // Linha de Botoes
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Esquerda: Modos VR
        val vrModesLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnGlass = VoidIconButton(context, R.drawable.icon_vr_headset, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            setOnClickListener {
                activity.openScreenFormatModal()
            }
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f))
        }
        vrModesLayout.addView(btnGlass)

        val btnSwapEyes = VoidIconButton(context, R.drawable.icon_glasses, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            setOnClickListener {
                activity.nativeToggleSwapEyes()
                // Efeito visual de confirmacao rapida
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(com.tucavr.designsystem.VoidTheme.colorSurfaceAlt)
                    cornerRadius = com.tucavr.designsystem.VoidTheme.dp(context, 200f)
                    setStroke(com.tucavr.designsystem.VoidTheme.dpToPx(context, 2f), com.tucavr.designsystem.VoidTheme.colorAccent)
                }
                postDelayed({
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#33FFFFFF")),
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.TRANSPARENT)
                            cornerRadius = com.tucavr.designsystem.VoidTheme.dp(context, 200f)
                        }, null
                    )
                }, 300)
            }
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f)).apply {
                leftMargin = VoidTheme.dpToPx(context, 8f)
            }
        }
        vrModesLayout.addView(btnSwapEyes)

        // Fase 0.3 Seção 2: Passthrough / Mixed Reality. Só sai de DISABLED
        // se o nativo (Vulkan) confirmar que a extensão XR_FB_passthrough
        // existe neste runtime. Estado ON = VoidButtonStyle.ACTIVE.
        val passthroughSupported = activity.nativeIsPassthroughSupported()
        val passthroughInitiallyOn =
            passthroughSupported && FeatureFlags.isEnabled(context, FeatureFlags.Flag.PASSTHROUGH)
        val btnPassthrough = VoidIconButton(
            context,
            R.drawable.icon_eye_dashed,
            when {
                !passthroughSupported -> VoidButtonStyle.DISABLED
                passthroughInitiallyOn -> VoidButtonStyle.ACTIVE
                else -> VoidButtonStyle.SECONDARY
            },
            isCircular = true,
            isTransparent = true,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f)).apply {
                leftMargin = VoidTheme.dpToPx(context, 8f)
            }
            if (passthroughSupported) {
                setOnClickListener {
                    val newState = !FeatureFlags.isEnabled(context, FeatureFlags.Flag.PASSTHROUGH)
                    FeatureFlags.setEnabled(context, FeatureFlags.Flag.PASSTHROUGH, newState)
                    activity.nativeSetPassthroughEnabled(newState)
                    style = if (newState) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
                }
            }
        }
        vrModesLayout.addView(btnPassthrough)
        controlsRow.addView(vrModesLayout)

        controlsRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f) })

        // Centro: Playback
        val playbackLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnRewind = VoidIconButton(context, R.drawable.icon_skip_back, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f))
            setOnClickListener {
                val currentProgress = (seekBar.progress / 100f) * totalDuration
                val newTarget = kotlin.math.max(0f, currentProgress - 10f)
                activity.nativeSeekVideo(newTarget)
            }
        }
        playbackLayout.addView(btnRewind)

        btnPlayPause = VoidIconButton(context, R.drawable.icon_pause, VoidButtonStyle.PRIMARY, isCircular = true, isTransparent = true).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f)).apply {
                leftMargin = VoidTheme.dpToPx(context, 97f)
                rightMargin = VoidTheme.dpToPx(context, 97f)
            }
            setOnClickListener { onPlayPause() }
        }
        playbackLayout.addView(btnPlayPause)

        val btnForward = VoidIconButton(context, R.drawable.icon_skip_forward, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f))
            setOnClickListener {
                val currentProgress = (seekBar.progress / 100f) * totalDuration
                val newTarget = kotlin.math.min(totalDuration, currentProgress + 10f)
                activity.nativeSeekVideo(newTarget)
            }
        }
        playbackLayout.addView(btnForward)
        controlsRow.addView(playbackLayout)

        controlsRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f) })

        // Direita: Utilitarios (Apenas os icones para bater com o Figma)
        val utilsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnSubtitles = VoidIconButton(context, R.drawable.icon_subtitles, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f)).apply {
                rightMargin = VoidTheme.dpToPx(context, 8f)
            }
            contentDescription = context.getString(R.string.player_btn_subtitles)
            setOnClickListener {
                activity.openSubtitlesModal()
            }
        }
        utilsLayout.addView(btnSubtitles)

        val btnAudio = VoidIconButton(context, R.drawable.ic_audio, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f)).apply {
                rightMargin = VoidTheme.dpToPx(context, 8f)
            }
            contentDescription = context.getString(R.string.player_btn_audio)
            setOnClickListener {
                activity.openAudioTracksModal()
            }
        }
        utilsLayout.addView(btnAudio)

        val btnVolume = VoidIconButton(context, R.drawable.icon_volume_2, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f))
        }
        utilsLayout.addView(btnVolume)
        
        val btnSpeed = VoidIconButton(context, R.drawable.icon_gauge, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f))
        }
        utilsLayout.addView(btnSpeed)

        btnDebugStats = VoidIconButton(context, R.drawable.icon_stats, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 88f), VoidTheme.dpToPx(context, 88f)).apply {
                leftMargin = VoidTheme.dpToPx(context, 8f)
            }
            contentDescription = context.getString(R.string.player_btn_debug_stats)
            visibility = if (activity.isDebugStatsEnabled) View.VISIBLE else View.GONE
            setOnClickListener {
                activity.openDebugStatsModal()
            }
        }
        utilsLayout.addView(btnDebugStats)

        controlsRow.addView(utilsLayout)
        bottomPanel.addView(controlsRow)

        // Linha da Timeline
        val timelineRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = VoidTheme.dpToPx(context, 32f)
            }
        }

        timeLabel = TextView(context).apply {
            text = "00:00"
            typeface = VoidTheme.typefaceMono
            textSize = 32f
            setTextColor(VoidTheme.colorText)
        }
        timelineRow.addView(timeLabel)

        seekBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(VoidTheme.dpToPx(context, 32f), 0, VoidTheme.dpToPx(context, 32f), 0)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
            thumbTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
            max = 100; progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && totalDuration > 0) updateScrubPreview((progress / 100f) * totalDuration)
                }
                override fun onStartTrackingTouch(p0: SeekBar?) { isDragging = true; startScrubPreview() }
                override fun onStopTrackingTouch(p0: SeekBar?) {
                    isDragging = false; stopScrubPreview()
                    lastSeekTime = System.currentTimeMillis()
                    if (totalDuration > 0) {
                        val target = (progress / 100f) * totalDuration
                        timeLabel.text = formatTime(target)
                        activity.nativeSeekVideo(target)
                    }
                }
            })
        }
        timelineRow.addView(seekBar)

        totalTimeLabel = TextView(context).apply {
            text = "00:00"
            typeface = VoidTheme.typefaceMono
            textSize = 32f
            setTextColor(VoidTheme.colorText)
        }
        timelineRow.addView(totalTimeLabel)
        
        bottomPanel.addView(timelineRow)

        root.addView(bottomPanel)

        val overlay = FrameLayout(context)
        overlay.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        loadingSpinner = ProgressBar(context).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
            visibility = View.GONE
        }
        overlay.addView(loadingSpinner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        scrubPreview = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(VoidTheme.colorSurface)
            visibility = View.GONE
        }
        overlay.addView(scrubPreview, FrameLayout.LayoutParams(VoidTheme.dpToPx(context, 160f), VoidTheme.dpToPx(context, 90f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = VoidTheme.dpToPx(context, 8f) })

        setContentView(overlay)

        window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
}
