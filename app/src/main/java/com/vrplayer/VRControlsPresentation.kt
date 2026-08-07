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
    private val onPlayPause: () -> Unit
) : Presentation(outerContext, display) {

    private lateinit var seekBar: SeekBar
    private var isDragging = false
    private var totalDuration = 0f
    private var volume = 1.0f
    private var speed = 1.0f

    fun updateProgress(currentSec: Float, totalSec: Float) {
        totalDuration = totalSec
        if (!isDragging && totalSec > 0) {
            seekBar.progress = ((currentSec / totalSec) * 100).toInt()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#88000000")) // Semi-transparent black
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        
        val btnRewind = Button(context).apply {
            text = "<<"
            textSize = 36f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(32, 32, 32, 32)
            setOnClickListener {
                if (context is VRActivity) {
                    val currentProgress = (seekBar.progress / 100f) * totalDuration
                    val newTarget = kotlin.math.max(0f, currentProgress - 10f)
                    (context as VRActivity).nativeSeekVideo(newTarget)
                }
            }
        }
        layout.addView(btnRewind)

        val btnPlayPause = Button(context).apply {
            text = "Play / Pause"
            textSize = 36f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(48, 32, 48, 32)
            setOnClickListener {
                onPlayPause()
            }
        }
        
        val marginParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(32, 0, 32, 0)
        }
        layout.addView(btnPlayPause, marginParams)

        val btnForward = Button(context).apply {
            text = ">>"
            textSize = 36f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(32, 32, 32, 32)
            setOnClickListener {
                if (context is VRActivity) {
                    val currentProgress = (seekBar.progress / 100f) * totalDuration
                    val newTarget = kotlin.math.min(totalDuration, currentProgress + 10f)
                    (context as VRActivity).nativeSeekVideo(newTarget)
                }
            }
        }
        layout.addView(btnForward)

        seekBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, // width = 0
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f // weight = 1
            ).apply {
                setMargins(64, 0, 32, 0)
            }
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, p1: Int, fromUser: Boolean) {
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {
                    isDragging = true
                }
                override fun onStopTrackingTouch(p0: SeekBar?) {
                    isDragging = false
                    if (context is VRActivity && totalDuration > 0) {
                        val newTarget = (progress / 100f) * totalDuration
                        (context as VRActivity).nativeSeekVideo(newTarget)
                    }
                }
            })
        }
        layout.addView(seekBar)

        val btnVolDown = Button(context).apply {
            text = "🔉-"
            textSize = 32f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(24, 32, 24, 32)
            setOnClickListener {
                volume = kotlin.math.max(0f, volume - 0.1f)
                (context as? VRActivity)?.nativeSetVolume(volume)
            }
        }
        layout.addView(btnVolDown, marginParams)

        val btnVolUp = Button(context).apply {
            text = "🔊+"
            textSize = 32f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(24, 32, 24, 32)
            setOnClickListener {
                volume = kotlin.math.min(1f, volume + 0.1f)
                (context as? VRActivity)?.nativeSetVolume(volume)
            }
        }
        layout.addView(btnVolUp, marginParams)

        val btnSpeedDown = Button(context).apply {
            text = "🐢"
            textSize = 32f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(24, 32, 24, 32)
            setOnClickListener {
                speed = kotlin.math.max(0.5f, speed - 0.25f)
                (context as? VRActivity)?.nativeSetSpeed(speed)
            }
        }
        layout.addView(btnSpeedDown, marginParams)

        val btnSpeedUp = Button(context).apply {
            text = "🐇"
            textSize = 32f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(24, 32, 24, 32)
            setOnClickListener {
                speed = kotlin.math.min(2.0f, speed + 0.25f)
                (context as? VRActivity)?.nativeSetSpeed(speed)
            }
        }
        layout.addView(btnSpeedUp, marginParams)

        val btnAudioTrack = Button(context).apply {
            text = "🎵"
            textSize = 32f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setPadding(24, 32, 24, 32)
            setOnClickListener {
                (context as? VRActivity)?.nativeCycleAudioTrack()
            }
        }
        layout.addView(btnAudioTrack, marginParams)

        setContentView(layout)
    }
}
