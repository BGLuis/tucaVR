package com.vrplayer

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.widget.LinearLayout
import android.graphics.Color
import android.view.Gravity
import android.widget.SeekBar
import android.widget.TextView
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidTheme

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
    private var isDragging = false
    private var totalDuration = 0f

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
                override fun onProgressChanged(p0: SeekBar?, p1: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(p0: SeekBar?) {
                    isDragging = true
                }
                override fun onStopTrackingTouch(p0: SeekBar?) {
                    isDragging = false
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

        setContentView(root)
        
        window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
}
