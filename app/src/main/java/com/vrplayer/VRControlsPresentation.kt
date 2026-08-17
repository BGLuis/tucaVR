package com.vrplayer

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
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.filebrowser.NetworkThumbnailGenerator
import com.vrplayer.filebrowser.ScrubStrip
import com.vrplayer.navigation.PlaybackSource
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

    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var speedBar: SeekBar
    private lateinit var btn3DMode: VoidButton
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var scrubPreview: ImageView
    private var debugHudLabel: TextView? = null
    private var isDragging = false
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

    // T1.4/T2.4/T2.5: indices DEVEM casar com ScreenMode em
    // native/src/vr_player_app.cpp e a codificacao numerica em
    // rust/bridge/src/lib.rs (cycle_3d_mode) — 10 modos agora que a
    // separacao real de olho (SBS/OU flat e esferico) esta implementada.
    private val modeLabelResIds = intArrayOf(
        R.string.player_mode_2d,
        R.string.player_mode_sbs,
        R.string.player_mode_sbs_half,
        R.string.player_mode_ou,
        R.string.player_mode_ou_half,
        R.string.player_mode_360,
        R.string.player_mode_180,
        R.string.player_mode_360_sbs,
        R.string.player_mode_360_ou,
        R.string.player_mode_180_sbs,
    )

    private fun modeLabel(mode: Int): String {
        val resId = modeLabelResIds.getOrElse(mode) { R.string.player_mode_2d }
        return context.getString(R.string.player_btn_3d_mode_format, context.getString(resId))
    }

    // Indices 5-9 de modeLabelResIds acima (360/180 e variantes estereo) usam
    // esfera, nao quad — o overlay de preview sobre o video (nativeUpdateScrubOverlay)
    // so cobre modo plano (ver comentario em VRActivity.nativeUpdateScrubOverlay).
    private fun isSphereMode(mode: Int) = mode >= 5

    // Resolucao pedida pro decode escalado de scrub local (ver updateScrubPreview) —
    // rapido o bastante pra acompanhar arrastos continuos, ainda reconhecivel.
    private companion object {
        const val LOCAL_SCRUB_WIDTH = 320
        const val LOCAL_SCRUB_HEIGHT = 180
    }

    private var lastKnownMode = 0

    fun updateProgress(currentSec: Float, totalSec: Float) {
        totalDuration = totalSec
        if (!isDragging && totalSec > 0) {
            seekBar.progress = ((currentSec / totalSec) * 100).toInt()
        }
        timeLabel.text = context.getString(
            R.string.player_controls_time_format, formatTime(currentSec), formatTime(totalSec)
        )

        // T9: cada novo playback (playFile/playSmb) reseta o modo 3D no lado
        // Rust (ver reset_3d_mode em rust/bridge/src/lib.rs) pra nao vazar o
        // modo do video anterior — resincroniza o texto do botao aqui, que
        // ja e chamado ~10x/s durante playback (ver frameCount%6 em
        // vr_player_app.cpp), em vez de adicionar um callback JNI dedicado
        // so pra isto.
        val mode = activity.nativeGet3DMode()
        if (mode != lastKnownMode) {
            lastKnownMode = mode
            btn3DMode.text = modeLabel(mode)
        }
    }

    /**
     * HUD de debug (ver VRActivity.updateDebugHud/docs/DEBUGGING.md) — texto
     * de diagnostico puro (ScreenMode/stereoLayout/polar180/swapEyes/estado
     * do frame de video), formatado do lado nativo. `debugHudLabel` so
     * existe (ver onCreate) se `activity.isDebuggable`; se for null aqui
     * (build de release, ou VRActivity.updateDebugHud ja filtrou antes de
     * chamar) o texto e descartado sem custo.
     */
    fun updateDebugHud(text: String) {
        debugHudLabel?.text = text
    }

    /**
     * Sinal de loading (ver VRActivity.updateMediaState), chamado ~10x/s
     * pelo C++. Feedback de play/pause/seek agora e desenhado sobre a quad
     * de video (native/src/vr_player_feedback_overlay.h), nao mais aqui no
     * painel — este metodo so cuida do spinner de loading.
     */
    fun updateMediaState(isLoading: Boolean, isPlaying: Boolean) {
        loadingSpinner.visibility = if (isLoading) View.VISIBLE else View.GONE
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Fundo "Void" semi-transparente (em vez do cinza-escuro
            // generico anterior) — flutua sobre a tela de video sem quebrar
            // a identidade visual do resto do app.
            setBackgroundColor(Color.argb(215, Color.red(VoidTheme.colorBackground), Color.green(VoidTheme.colorBackground), Color.blue(VoidTheme.colorBackground)))
            val padH = VoidTheme.dpToPx(context, 24f)
            val padV = VoidTheme.dpToPx(context, 16f)
            setPadding(padH, padV, padH, padV)
        }

        val marginParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f), 0)
        }

        // --- Linha 1: transporte (rewind / play-pause / forward) + seek + tempo ---
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnRewind = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = "<<"
            textSize = 24f
            setOnClickListener {
                val currentProgress = (seekBar.progress / 100f) * totalDuration
                val newTarget = kotlin.math.max(0f, currentProgress - 10f)
                activity.nativeSeekVideo(newTarget)
            }
        }
        row1.addView(btnRewind)

        val btnPlayPause = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.player_btn_play_pause)
            textSize = 22f
            setOnClickListener { onPlayPause() }
        }
        row1.addView(btnPlayPause, marginParams)

        val btnForward = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = ">>"
            textSize = 24f
            setOnClickListener {
                val currentProgress = (seekBar.progress / 100f) * totalDuration
                val newTarget = kotlin.math.min(totalDuration, currentProgress + 10f)
                activity.nativeSeekVideo(newTarget)
            }
        }
        row1.addView(btnForward)

        seekBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 8f), 0)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
            thumbTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                    android.util.Log.i("VRPlayer_Scrub", "onProgressChanged: progress=$progress fromUser=$fromUser totalDuration=$totalDuration")
                    if (fromUser && totalDuration > 0) {
                        updateScrubPreview((progress / 100f) * totalDuration)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {
                    isDragging = true
                    startScrubPreview()
                }
                override fun onStopTrackingTouch(p0: SeekBar?) {
                    isDragging = false
                    stopScrubPreview()
                    if (totalDuration > 0) {
                        activity.nativeSeekVideo((progress / 100f) * totalDuration)
                    }
                }
            })
        }
        row1.addView(seekBar)

        timeLabel = TextView(context).apply {
            text = context.getString(R.string.player_controls_time_format, "00:00", "00:00")
            typeface = VoidTheme.typefaceMono
            textSize = 16f
            setTextColor(VoidTheme.colorText)
            setPadding(VoidTheme.dpToPx(context, 8f), 0, 0, 0)
        }
        row1.addView(timeLabel)

        root.addView(row1)

        // --- Linha 2: volume, velocidade (sliders, nao botoes de incremento) e trilha de audio ---
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, 0)
        }

        val volumeLabel = TextView(context).apply {
            text = context.getString(R.string.player_controls_volume_format, 100)
            typeface = VoidTheme.typefaceBody
            textSize = 16f
            setTextColor(VoidTheme.colorText)
        }
        row2.addView(volumeLabel)

        volumeBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(VoidTheme.dpToPx(context, 12f), 0, VoidTheme.dpToPx(context, 28f), 0)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
            thumbTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                    volumeLabel.text = context.getString(R.string.player_controls_volume_format, progress)
                    if (fromUser) {
                        activity.nativeSetVolume(progress / 100f)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }
        row2.addView(volumeBar)

        val speedLabel = TextView(context).apply {
            text = context.getString(R.string.player_controls_speed_format, speedFromProgress(33))
            typeface = VoidTheme.typefaceBody
            textSize = 16f
            setTextColor(VoidTheme.colorText)
        }
        row2.addView(speedLabel)

        speedBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(VoidTheme.dpToPx(context, 12f), 0, VoidTheme.dpToPx(context, 28f), 0)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
            thumbTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
            max = 100
            // speedFromProgress(33) ~= 1.0x — valor inicial neutro
            progress = 33
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                    val speed = speedFromProgress(progress)
                    speedLabel.text = context.getString(R.string.player_controls_speed_format, speed)
                    if (fromUser) {
                        activity.nativeSetSpeed(speed)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }
        row2.addView(speedBar)

        val btnAudioTrack = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = "🎵"
            textSize = 20f
            setOnClickListener {
                activity.nativeCycleAudioTrack()
            }
        }
        row2.addView(btnAudioTrack)

        root.addView(row2)

        // --- Linha 3: modo de exibicao 3D (T1.4) + swap eyes (T1.5) ---
        // So relevante pra conteudo 3D/360/180; fica sempre visivel (nao ha
        // sinal ainda de "este arquivo e realmente 3D" chegando ate aqui —
        // auto-deteccao, T3.4, ainda nao esta ligada a UI) em vez de
        // esconder condicionalmente e arriscar o usuario nao achar o botao
        // pra um arquivo que a deteccao errou.
        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, 0)
        }

        btn3DMode = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = modeLabel(0)
            textSize = 16f
            setOnClickListener {
                text = modeLabel(activity.nativeCycle3DMode())
            }
        }
        row3.addView(btn3DMode, marginParams)

        val btnSwapEyes = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.player_btn_swap_eyes)
            textSize = 16f
            setOnClickListener {
                activity.nativeToggleSwapEyes()
            }
        }
        row3.addView(btnSwapEyes, marginParams)

        root.addView(row3)

        // --- Linha 4 (so em build debuggable): HUD de diagnostico de video ---
        // Ver docs/DEBUGGING.md. Mono/pequeno de proposito — e um dump de
        // estado pra depuracao, nao parte da UI de producao; nao precisa de
        // string de recurso/i18n (texto tecnico, sempre em pt-BR/ingles
        // misto vindo direto do C++, nunca visto por usuario final numa
        // build de release).
        if (activity.isDebuggable) {
            debugHudLabel = TextView(context).apply {
                text = "debug hud"
                typeface = VoidTheme.typefaceMono
                textSize = 12f
                setTextColor(VoidTheme.colorAccent)
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
            }
            root.addView(debugHudLabel)
        }

        // FrameLayout envolvendo `root` so pra poder sobrepor o spinner de
        // loading centralizado por cima do painel inteiro, sem entrar no
        // fluxo vertical do LinearLayout das linhas 1-4 acima.
        val overlay = FrameLayout(context)
        overlay.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        loadingSpinner = ProgressBar(context).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
            visibility = View.GONE
        }
        overlay.addView(loadingSpinner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))

        // Preview de arrasto (T-seek-ux) — flutua ACIMA do seekbar (linha 1
        // de `root`), escalado bem maior que a resolucao nativa (80x45, ver
        // ScrubStrip) porque e so um preview grosseiro, nao uma miniatura
        // de qualidade.
        scrubPreview = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(VoidTheme.colorSurface)
            visibility = View.GONE
        }
        overlay.addView(
            scrubPreview,
            FrameLayout.LayoutParams(
                VoidTheme.dpToPx(context, 160f), VoidTheme.dpToPx(context, 90f), Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = VoidTheme.dpToPx(context, 8f) }
        )

        setContentView(overlay)

        window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
}
