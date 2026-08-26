package com.tucavr

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.tucavr.designsystem.AudioTrackModal
import com.tucavr.designsystem.DebugStatsModal
import com.tucavr.designsystem.ResumePromptModal
import com.tucavr.designsystem.ScreenFormatModal
import com.tucavr.designsystem.SubtitleSelectionModal
import com.tucavr.filebrowser.MediaMetadataReader
import com.tucavr.history.PlaybackHistory
import com.tucavr.history.historyKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Apresentação dedicada para o 3º Quad 3D no OpenXR (painel modal frontal flutuante).
 *
 * Desacoplada do painel esquerdo (`VRPresentation`) e do painel inferior (`VRControlsPresentation`).
 * Permite exibir qualquer diálogo ou modal frontal (ex.: seleção de formato de tela 3D ou Estatísticas Técnicas)
 * flutuando diretamente na linha de visão do usuário com transparência completa externa.
 */
class VRModalPresentation(
    private val activity: VRActivity,
    display: Display,
    context: Context = activity
) : Presentation(context, display) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var rootContainer: FrameLayout
    private var currentModalView: View? = null
    private var currentDebugStatsModal: DebugStatsModal? = null
    private var lastStatsWire: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootContainer = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        setContentView(rootContainer)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    /**
     * Exibe uma view modal genérica no container frontal.
     */
    fun showModal(view: View) {
        rootContainer.removeAllViews()
        rootContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        currentModalView = view
        rootContainer.visibility = View.VISIBLE
        rootContainer.requestLayout()
        rootContainer.invalidate()
        activity.nativeShowModalPanel()
    }

    /**
     * Fecha o modal ativo e limpa a superfície.
     */
    fun dismissModal() {
        if (currentModalView == null) return
        rootContainer.removeAllViews()
        currentModalView = null
        currentDebugStatsModal = null
        rootContainer.setBackgroundColor(Color.TRANSPARENT)
        activity.nativeHideModalPanel()
    }

    /**
     * Prepara e exibe o modal de estatísticas técnicas ([DebugStatsModal]).
     */
    fun showDebugStatsModal() {
        val modal = DebugStatsModal(
            context = context,
            onDismiss = { dismissModal() }
        )
        currentDebugStatsModal = modal
        showModal(modal)

        lastStatsWire?.let { wire ->
            modal.updateStats(
                text = wire,
                meta = activity.currentMediaMetadata,
                source = activity.currentPlaybackSource,
                isCharging = activity.isBatteryCharging(),
                batteryPercent = activity.getBatteryPercent(),
                isDebuggable = activity.isDebuggable
            )
        }
    }

    /**
     * Atualiza as estatísticas no modal se estiver ativo.
     */
    fun updateDebugStats(text: String) {
        lastStatsWire = text
        val modal = currentDebugStatsModal ?: return
        val batteryPct = activity.getBatteryPercent()
        val isCharging = activity.isBatteryCharging()
        modal.updateStats(
            text = text,
            meta = activity.currentMediaMetadata,
            source = activity.currentPlaybackSource,
            isCharging = isCharging,
            batteryPercent = batteryPct,
            isDebuggable = activity.isDebuggable
        )
    }

    /**
     * Prepara e exibe o modal de formato de tela ([ScreenFormatModal]).
     */
    fun showScreenFormatModal() {
        val currentSource = activity.currentPlaybackSource
        val currentMode = activity.nativeGet3DMode()

        scope.launch {
            val meta = currentSource?.let { src ->
                MediaMetadataReader.read(activity, src)
            }
            val detectedMode = meta?.format3dIndex
            val confidence = meta?.detectionConfidence ?: 3

            val modal = ScreenFormatModal(
                context = context,
                currentMode = currentMode,
                detectedMode = detectedMode,
                detectionConfidence = confidence,
                onModeSelected = { mode ->
                    activity.nativeSetScreenMode(mode)
                    currentSource?.let { src ->
                        activity.format3dStore.set(src.historyKey(), mode)
                    }
                },
                onUseAutoDetection = {
                    activity.nativeSetScreenModeOverride(-1)
                    currentSource?.let { src ->
                        activity.format3dStore.clear(src.historyKey())
                    }
                    if (detectedMode != null && detectedMode in 0..9) {
                        activity.nativeSetScreenMode(detectedMode)
                    }
                },
                onDismiss = {
                    dismissModal()
                }
            )
            showModal(modal)
        }
    }

    /**
     * Prepara e exibe o modal de seleção e sincronização de legendas ([SubtitleSelectionModal]).
     */
    fun showSubtitlesModal() {
        val trackCount = activity.nativeGetSubtitleTrackCount()
        val currentTrack = activity.nativeGetSubtitleTrack()
        val currentOffsetMs = activity.nativeGetSubtitleOffsetMs()

        val modal = SubtitleSelectionModal(
            context = context,
            trackCount = trackCount,
            currentTrack = currentTrack,
            currentOffsetMs = currentOffsetMs,
            onTrackSelected = { trackIndex ->
                activity.nativeSetSubtitleTrack(trackIndex)
            },
            onOffsetChanged = { offsetMs ->
                activity.nativeSetSubtitleOffsetMs(offsetMs)
            },
            onDismiss = { dismissModal() }
        )
        showModal(modal)
    }

    /**
     * Prepara e exibe o modal de seleção de faixas de áudio ([AudioTrackModal]).
     */
    fun showAudioTracksModal() {
        val currentSource = activity.currentPlaybackSource
        scope.launch {
            val meta = activity.currentMediaMetadata ?: currentSource?.let { src ->
                MediaMetadataReader.read(activity, src)
            }
            val tracks = meta?.tracks ?: emptyList()
            val modal = AudioTrackModal(
                context = context,
                tracks = tracks,
                activeOrdinal = activity.currentAudioTrackOrdinal,
                onTrackSelected = { ordinal ->
                    activity.switchAudioTrack(ordinal)
                },
                onDismiss = { dismissModal() }
            )
            showModal(modal)
        }
    }

    /**
     * Prepara e exibe o modal de prompt de retomada ([ResumePromptModal]).
     */
    fun showResumePromptModal(
        entry: PlaybackHistory,
        onResume: () -> Unit,
        onRestart: () -> Unit
    ) {
        val modal = ResumePromptModal(
            context = context,
            entry = entry,
            onResume = {
                dismissModal()
                onResume()
            },
            onRestart = {
                dismissModal()
                onRestart()
            },
            onDismiss = { dismissModal() }
        )
        showModal(modal)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }
}
