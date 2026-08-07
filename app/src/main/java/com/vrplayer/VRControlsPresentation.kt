package com.vrplayer

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.widget.Button
import android.widget.LinearLayout
import android.graphics.Color
import android.view.Gravity
import android.widget.SeekBar
import android.widget.TextView

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
) : Presentation(outerContext, display) {

    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var speedBar: SeekBar
    private var isDragging = false
    private var totalDuration = 0f

    fun updateProgress(currentSec: Float, totalSec: Float) {
        totalDuration = totalSec
        if (!isDragging && totalSec > 0) {
            seekBar.progress = ((currentSec / totalSec) * 100).toInt()
        }
        timeLabel.text = "${formatTime(currentSec)} / ${formatTime(totalSec)}"
    }

    private fun formatTime(seconds: Float): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return String.format("%02d:%02d", total / 60, total % 60)
    }

    private fun speedFromProgress(progress: Int): Float = 0.5f + (progress / 100f) * 1.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#88000000")) // Semi-transparent black
            setPadding(24, 16, 24, 16)
        }

        val marginParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(24, 0, 24, 0)
        }

        // --- Linha 1: transporte (rewind / play-pause / forward) + seek + tempo ---
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnRewind = Button(context).apply {
            text = "<<"
            textSize = 30f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(28, 20, 28, 20)
            setOnClickListener {
                val currentProgress = (seekBar.progress / 100f) * totalDuration
                val newTarget = kotlin.math.max(0f, currentProgress - 10f)
                activity.nativeSeekVideo(newTarget)
            }
        }
        row1.addView(btnRewind)

        val btnPlayPause = Button(context).apply {
            text = "Play / Pause"
            textSize = 30f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(40, 20, 40, 20)
            setOnClickListener { onPlayPause() }
        }
        row1.addView(btnPlayPause, marginParams)

        val btnForward = Button(context).apply {
            text = ">>"
            textSize = 30f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(28, 20, 28, 20)
            setOnClickListener {
                val currentProgress = (seekBar.progress / 100f) * totalDuration
                val newTarget = kotlin.math.min(totalDuration, currentProgress + 10f)
                activity.nativeSeekVideo(newTarget)
            }
        }
        row1.addView(btnForward)

        seekBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(48, 0, 24, 0)
            }
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
            text = "00:00 / 00:00"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(16, 0, 0, 0)
        }
        row1.addView(timeLabel)

        root.addView(row1)

        // --- Linha 2: volume, velocidade (sliders, nao botoes de incremento) e trilha de audio ---
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 0)
        }

        val volumeLabel = TextView(context).apply {
            text = "🔊 100%"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        row2.addView(volumeLabel)

        volumeBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(16, 0, 40, 0)
            }
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                    volumeLabel.text = "🔊 $progress%"
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
            text = "⏱ 1.00x"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        row2.addView(speedLabel)

        speedBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(16, 0, 40, 0)
            }
            max = 100
            // speedFromProgress(33) ~= 1.0x — valor inicial neutro
            progress = 33
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                    val speed = speedFromProgress(progress)
                    speedLabel.text = String.format("⏱ %.2fx", speed)
                    if (fromUser) {
                        activity.nativeSetSpeed(speed)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }
        row2.addView(speedBar)

        val btnAudioTrack = Button(context).apply {
            text = "🎵"
            textSize = 26f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                activity.nativeCycleAudioTrack()
            }
        }
        row2.addView(btnAudioTrack)

        root.addView(row2)

        setContentView(root)
    }
}
