package com.tucavr

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.tucavr.designsystem.AudioTrackModal
import com.tucavr.designsystem.EnvironmentSelectorModal
import com.tucavr.designsystem.PlaylistModal
import com.tucavr.designsystem.ScreenFormatModal
import com.tucavr.designsystem.ScreenSettingsModal
import com.tucavr.designsystem.SpeedMenuModal
import com.tucavr.designsystem.SubtitleSelectionModal
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.designsystem.VoidTheme
import com.tucavr.designsystem.VolumeMenuModal
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ScrubStrip
import com.tucavr.history.historyKey
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FisheyeFovPreferenceStore
import com.tucavr.playlist.PlaybackMode
import com.tucavr.playlist.PlaylistItem
import com.tucavr.playlist.PlaylistQueueManager
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
    // `Presentation.context` (inherited from Dialog) is NOT `outerContext` — Android creates
    // a ContextThemeWrapper around a display-context derived from it. A cast `context as? VRActivity`
    // fails (null), rendering `?.nativeX(...)` silent no-ops. We store the actual Activity explicitly.
    private val activity: VRActivity,
    private val onPlayPause: () -> Unit,
) : Presentation(outerContext, display, android.R.style.Theme_NoTitleBar_Fullscreen) {
    internal val popupCoordinator = ControlsPopupCoordinator()
    private var currentPopupView: View? = null
    private var currentSpeed: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var isScreenCurved: Boolean = false

    private lateinit var popupContainer: FrameLayout
    private lateinit var seekBar: SeekBar
    private lateinit var scrubTooltip: TextView
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryLabel: TextView
    private lateinit var thermalIcon: ImageView
    private lateinit var thermalLabel: TextView
    private lateinit var qualityBadge: TextView
    private lateinit var clockLabel: TextView
    private lateinit var btnPlayPause: VoidIconButton
    private lateinit var btnFormat: VoidIconButton
    private lateinit var btnScreen: VoidIconButton
    private lateinit var btnEnvironment: VoidIconButton
    private lateinit var btnSubtitles: VoidIconButton
    private lateinit var btnAudio: VoidIconButton
    private lateinit var btnSpeed: TextView
    private lateinit var btnVolume: VoidIconButton
    private lateinit var btnDebugStats: VoidIconButton
    private lateinit var btnPrevious: VoidIconButton
    private lateinit var btnNext: VoidIconButton
    private lateinit var btnQueue: VoidIconButton

    private var hudReceiver: android.content.BroadcastReceiver? = null
    private lateinit var timeLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var totalTimeLabel: TextView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var scrubPreview: ImageView
    private var btnPassthrough: VoidIconButton? = null

    private var lastKnownBatteryPercent: Int = 100
    private var lastKnownIsCharging: Boolean = false
    private var isDragging = false
    private var lastSeekTime = 0L
    private var totalDuration = 0f
    private var lastKnownMode = 0
    private var lastKnownCurrentSec = 0f

    private val scrubScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var networkScrubStrip: ScrubStrip? = null
    private var localScrubRetriever: MediaMetadataRetriever? = null
    private var localScrubJob: Job? = null
    private var networkScrubJob: Job? = null

    private val playlistListener =
        object : PlaylistQueueManager.Listener {
            override fun onQueueChanged() {
                activity.runOnUiThread { updatePlaylistVisibility() }
            }

            override fun onItemChanged(
                item: PlaylistItem?,
                index: Int,
            ) {
                activity.runOnUiThread { updatePlaylistVisibility() }
            }

            override fun onModeChanged(mode: PlaybackMode) {}
        }

    companion object {
        const val MIN_INTERACTIVE_BUTTON_DP = 72f
        const val PRIMARY_INTERACTIVE_BUTTON_DP = 96f
        const val LOCAL_SCRUB_WIDTH = 320
        const val LOCAL_SCRUB_HEIGHT = 180
        const val SEEK_BAR_MAX = 10000

        internal fun formatPlaybackTime(
            seconds: Float,
            totalDuration: Float = 0f,
        ): String {
            val total = seconds.toInt().coerceAtLeast(0)
            val hrs = total / 3600
            val mins = (total % 3600) / 60
            val secs = total % 60
            return if (totalDuration >= 3600f || hrs > 0) {
                String.format("%02d:%02d:%02d", hrs, mins, secs)
            } else {
                String.format("%02d:%02d", mins, secs)
            }
        }
    }

    fun hasOpenPopup(): Boolean = popupCoordinator.hasOpenPopup

    fun dismissOpenPopup(): Boolean {
        if (popupCoordinator.dismiss()) {
            popupContainer.removeAllViews()
            popupContainer.visibility = View.GONE
            currentPopupView = null
            highlightActiveButton(null)
            return true
        }
        return false
    }

    private fun toggleMenu(type: ControlsMenuType) {
        val opened = popupCoordinator.toggleMenu(type)
        if (!opened) {
            popupContainer.removeAllViews()
            popupContainer.visibility = View.GONE
            currentPopupView = null
            highlightActiveButton(null)
            return
        }
        popupContainer.removeAllViews()
        val view =
            when (type) {
                ControlsMenuType.FORMAT_3D -> createFormat3dModal()
                ControlsMenuType.SCREEN -> createScreenModal()
                ControlsMenuType.ENVIRONMENT -> createEnvironmentModal()
                ControlsMenuType.SUBTITLES -> createSubtitlesModal()
                ControlsMenuType.AUDIO -> createAudioModal()
                ControlsMenuType.SPEED -> createSpeedModal()
                ControlsMenuType.VOLUME -> createVolumeModal()
                ControlsMenuType.PLAYLIST -> createPlaylistModal()
            }
        currentPopupView = view
        highlightActiveButton(type)
        popupContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        popupContainer.visibility = View.VISIBLE
    }

    private fun highlightActiveButton(activeType: ControlsMenuType?) {
        if (::btnFormat.isInitialized) {
            btnFormat.style = if (activeType == ControlsMenuType.FORMAT_3D) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
        if (::btnScreen.isInitialized) {
            btnScreen.style = if (activeType == ControlsMenuType.SCREEN) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
        if (::btnEnvironment.isInitialized) {
            btnEnvironment.style = if (activeType == ControlsMenuType.ENVIRONMENT) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
        if (::btnQueue.isInitialized) {
            btnQueue.style = if (activeType == ControlsMenuType.PLAYLIST) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
        if (::btnSubtitles.isInitialized) {
            btnSubtitles.style = if (activeType == ControlsMenuType.SUBTITLES) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
        if (::btnAudio.isInitialized) {
            btnAudio.style = if (activeType == ControlsMenuType.AUDIO) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
        if (::btnSpeed.isInitialized) {
            val isActive = activeType == ControlsMenuType.SPEED
            btnSpeed.background =
                GradientDrawable().apply {
                    setColor(if (isActive) VoidTheme.colorAccent else VoidTheme.colorSurface)
                    cornerRadius = VoidTheme.dp(context, 36f)
                    setStroke(
                        VoidTheme.dpToPx(context, 1.5f),
                        if (isActive) VoidTheme.colorAccent else VoidTheme.colorBorder,
                    )
                }
            btnSpeed.setTextColor(if (isActive) VoidTheme.colorBackground else VoidTheme.colorText)
        }
        if (::btnVolume.isInitialized) {
            btnVolume.style = if (activeType == ControlsMenuType.VOLUME) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
    }

    private fun createFormat3dModal(): View {
        val currentSource = activity.currentPlaybackSource
        val currentMode = activity.nativeGet3DMode()
        val meta = activity.currentMediaMetadata
        val detectedMode = meta?.format3dIndex
        val confidence = meta?.detectionConfidence ?: 3
        val currentFov =
            currentSource?.let { src -> activity.fisheyeFovStore.get(src.historyKey()) }
                ?: FisheyeFovPreferenceStore.DEFAULT_FOV
        return ScreenFormatModal(
            context = context,
            currentMode = currentMode,
            detectedMode = detectedMode,
            detectionConfidence = confidence,
            isSwapEyes = { activity.nativeGetSwapEyes() },
            onToggleSwapEyes = { activity.nativeToggleSwapEyes() != 0 },
            isMonoView = { activity.nativeGetMonoView() },
            onToggleMonoView = { activity.nativeToggleMonoView() },
            currentFisheyeFov = currentFov,
            onFisheyeFovSelected = { fov ->
                activity.nativeSetFisheyeFov(fov)
                currentSource?.let { src ->
                    activity.fisheyeFovStore.set(src.historyKey(), fov)
                }
            },
            onModeSelected = { mode ->
                activity.nativeSetScreenMode(mode)
                currentSource?.let { src ->
                    activity.format3dStore.set(src.historyKey(), mode)
                }
                lastKnownMode = mode
                dismissOpenPopup()
            },
            onUseAutoDetection = {
                activity.nativeSetScreenModeOverride(-1)
                currentSource?.let { src ->
                    activity.format3dStore.clear(src.historyKey())
                }
                if (detectedMode != null && ScreenFormatCatalog.isValid(detectedMode)) {
                    activity.nativeSetScreenMode(detectedMode)
                    lastKnownMode = detectedMode
                }
                dismissOpenPopup()
            },
            onDismiss = { dismissOpenPopup() },
        )
    }

    private fun createScreenModal(): View {
        val currentEnv = activity.environmentStore.getActiveEnvironment()
        val currentT = activity.screenTransformStore.get(currentEnv)
        return ScreenSettingsModal(
            context = context,
            currentScaleX = currentT.scaleX,
            currentDistanceZ = currentT.posZ,
            isCurved = isScreenCurved,
            onScaleSelected = { scaleX ->
                val t = activity.screenTransformStore.get(currentEnv)
                val scaleY = scaleX * (9.0f / 16.0f)
                activity.nativeSetScreenTransform(t.posX, t.posY, t.posZ, scaleX, scaleY)
                activity.screenTransformStore.save(currentEnv, t.posX, t.posY, t.posZ, scaleX, scaleY)
            },
            onDistanceSelected = { posZ ->
                val t = activity.screenTransformStore.get(currentEnv)
                activity.nativeSetScreenTransform(t.posX, t.posY, posZ, t.scaleX, t.scaleY)
                activity.screenTransformStore.save(currentEnv, t.posX, t.posY, posZ, t.scaleX, t.scaleY)
            },
            onCurvedToggled = { curved ->
                isScreenCurved = curved
                activity.setBrowserGeometryMode(curved)
            },
            onRecenter = {
                activity.screenTransformStore.reset(currentEnv)
                val defaultT = ScreenTransformStore.getDefaultTransform(currentEnv)
                activity.nativeSetScreenTransform(defaultT.posX, defaultT.posY, defaultT.posZ, defaultT.scaleX, defaultT.scaleY)
                activity.nativeResetScreenPosition()
                dismissOpenPopup()
            },
            onDismiss = { dismissOpenPopup() },
        )
    }

    private fun createEnvironmentModal(): View {
        val currentEnv = activity.environmentStore.getActiveEnvironment()
        return EnvironmentSelectorModal(
            context = context,
            activeEnvironmentId = currentEnv,
            onEnvironmentSelected = { envId ->
                activity.setVirtualEnvironment(envId)
                dismissOpenPopup()
            },
            onResetAnchor = {
                activity.nativeResetScreenPosition()
            },
            onImportEnvironment = {
                VRActivity.openEnvironmentFilePicker(activity)
            },
            onDismiss = { dismissOpenPopup() },
        )
    }

    private fun createSubtitlesModal(): View {
        val trackCount = activity.nativeGetSubtitleTrackCount()
        val currentTrack = activity.nativeGetSubtitleTrack()
        val currentOffsetMs = activity.nativeGetSubtitleOffsetMs()
        return SubtitleSelectionModal(
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
            onDismiss = { dismissOpenPopup() },
        )
    }

    private fun createAudioModal(): View {
        val tracks = activity.currentMediaMetadata?.tracks ?: emptyList()
        return AudioTrackModal(
            context = context,
            tracks = tracks,
            activeOrdinal = activity.currentAudioTrackOrdinal,
            onTrackSelected = { ordinal ->
                activity.switchAudioTrack(ordinal)
                dismissOpenPopup()
            },
            onDismiss = { dismissOpenPopup() },
        )
    }

    private fun createSpeedModal(): View {
        return SpeedMenuModal(
            context = context,
            currentSpeed = currentSpeed,
            onSpeedSelected = { speed ->
                currentSpeed = speed
                activity.nativeSetSpeed(speed)
                updateSpeedButtonLabel(speed)
            },
            onDismiss = { dismissOpenPopup() },
        )
    }

    private fun createVolumeModal(): View {
        return VolumeMenuModal(
            context = context,
            currentVolume = currentVolume,
            onVolumeChanged = { volume ->
                currentVolume = volume
                activity.nativeSetVolume(volume)
            },
            onDismiss = { dismissOpenPopup() },
        )
    }

    private fun createPlaylistModal(): View {
        return PlaylistModal(
            context = context,
            queueManager = activity.playlistQueueManager,
            onPlayIndex = { index ->
                activity.playlistQueueManager.skipTo(index)
                dismissOpenPopup()
            },
            onDismiss = { dismissOpenPopup() },
        )
    }

    private fun formatSpeedText(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}×"
        } else {
            val formatted = String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
            "$formatted×"
        }
    }

    private fun updateSpeedButtonLabel(speed: Float) {
        if (::btnSpeed.isInitialized) {
            btnSpeed.text = formatSpeedText(speed)
        }
    }

    fun updateTitle(title: String) {
        if (::titleLabel.isInitialized) {
            titleLabel.text = title
        }
    }

    fun resetPlaybackState() {
        stopScrubPreview()
        dismissOpenPopup()
        if (::timeLabel.isInitialized) timeLabel.text = "00:00"
        if (::totalTimeLabel.isInitialized) totalTimeLabel.text = "00:00"
        if (::titleLabel.isInitialized) titleLabel.text = ""
        if (::scrubTooltip.isInitialized) {
            scrubTooltip.visibility = View.GONE
        }
        if (::seekBar.isInitialized) {
            seekBar.progress = 0
            seekBar.secondaryProgress = 0
        }
        totalDuration = 0f
        lastKnownCurrentSec = 0f
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.setImageResource(R.drawable.icon_play)
        }
        if (::loadingSpinner.isInitialized) {
            loadingSpinner.visibility = View.GONE
        }
    }

    fun updateProgress(
        currentSec: Float,
        totalSec: Float,
    ) {
        totalDuration = totalSec
        lastKnownCurrentSec = currentSec
        if (!isDragging && totalSec > 0 && (System.currentTimeMillis() - lastSeekTime > 800)) {
            seekBar.progress = ((currentSec / totalSec) * SEEK_BAR_MAX).toInt()
            timeLabel.text = formatTime(currentSec)
        }
        if (::totalTimeLabel.isInitialized) {
            totalTimeLabel.text = formatTime(totalSec)
        }
        lastKnownMode = activity.nativeGet3DMode()
    }

    fun updateBufferedProgress(bufferedAheadSec: Float) {
        if (!::seekBar.isInitialized || totalDuration <= 0f) return
        val bufferedEndSec = (lastKnownCurrentSec + bufferedAheadSec).coerceAtMost(totalDuration)
        seekBar.secondaryProgress = ((bufferedEndSec / totalDuration) * SEEK_BAR_MAX).toInt().coerceIn(0, SEEK_BAR_MAX)
    }

    fun onDebugStatsFlagChanged(enabled: Boolean) {
        if (::btnDebugStats.isInitialized) {
            btnDebugStats.visibility = if (enabled) View.VISIBLE else View.GONE
        }
    }

    fun onThermalStateChanged(state: ThermalMonitor.ThermalState) {
        if (!::thermalIcon.isInitialized || !::thermalLabel.isInitialized) return
        when (state.level) {
            ThermalMonitor.ThermalLevel.NORMAL,
            ThermalMonitor.ThermalLevel.LIGHT,
            -> {
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
            ThermalMonitor.ThermalLevel.SHUTDOWN,
            -> {
                val color = android.graphics.Color.parseColor("#FF3333")
                thermalIcon.visibility = View.VISIBLE
                thermalIcon.setColorFilter(color)
                thermalLabel.visibility = View.VISIBLE
                thermalLabel.text = context.getString(R.string.thermal_level_critical)
                thermalLabel.setTextColor(color)
            }
        }
    }

    fun updateQualityBadge(
        level: String,
        reason: String,
    ) {
        if (!::qualityBadge.isInitialized) return
        when (level) {
            "ULTRA", "HIGH" -> {
                qualityBadge.visibility = View.GONE
            }
            "MEDIUM" -> {
                qualityBadge.visibility = View.VISIBLE
                qualityBadge.text = "AQ: MED"
                qualityBadge.setTextColor(Color.parseColor("#FFCC00"))
            }
            "LOW" -> {
                qualityBadge.visibility = View.VISIBLE
                qualityBadge.text = if (reason != "NONE") "AQ: LOW ($reason)" else "AQ: LOW"
                qualityBadge.setTextColor(Color.parseColor("#FF8800"))
            }
            "EMERGENCY" -> {
                qualityBadge.visibility = View.VISIBLE
                qualityBadge.text = if (reason != "NONE") "AQ: CRIT ($reason)" else "AQ: CRIT"
                qualityBadge.setTextColor(Color.parseColor("#FF3333"))
            }
            else -> {
                qualityBadge.visibility = View.GONE
            }
        }
    }

    fun updateMediaState(
        isLoading: Boolean,
        isPlaying: Boolean,
    ) {
        loadingSpinner.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.setImageResource(if (isPlaying) R.drawable.icon_pause else R.drawable.icon_play)
        }
    }

    fun updatePlaylistVisibility() {
        val isPlaylistActive = activity.playlistQueueManager.currentPlaylist != null
        val vis = if (isPlaylistActive) View.VISIBLE else View.GONE
        if (::btnPrevious.isInitialized) btnPrevious.visibility = vis
        if (::btnNext.isInitialized) btnNext.visibility = vis
        if (::btnQueue.isInitialized) btnQueue.visibility = vis
    }

    private fun isSphereMode(mode: Int) = ScreenFormatCatalog.isSpherical(mode)

    private fun startScrubPreview() {
        if (!FeatureFlags.isEnabled(context, FeatureFlags.Flag.SCRUB_PREVIEW)) return
        activity.nativeSetScrubOverlayVisible(true)
        when (val source = activity.currentPlaybackSource) {
            is PlaybackSource.Smb, is PlaybackSource.Sftp -> {
                networkScrubJob =
                    scrubScope.launch {
                        networkScrubStrip = NetworkThumbnailGenerator.getScrubStrip(context, activity, source)
                    }
            }
            is PlaybackSource.LocalFile -> {
                localScrubRetriever =
                    MediaMetadataRetriever().apply {
                        runCatching { setDataSource(source.path) }
                    }
            }
            else -> {}
        }
    }

    private fun updateScrubPreview(positionSeconds: Float) {
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
        localScrubJob =
            scrubScope.launch {
                val bitmap =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val timeUs = (positionSeconds * 1_000_000).toLong()
                            if (android.os.Build.VERSION.SDK_INT >= 27) {
                                retriever.getScaledFrameAtTime(
                                    timeUs,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    LOCAL_SCRUB_WIDTH,
                                    LOCAL_SCRUB_HEIGHT,
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
                    val argbBitmap =
                        if (bitmap.config == Bitmap.Config.ARGB_8888) {
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
        activity.nativeCancelScrubStrip()
        networkScrubStrip = null
        localScrubRetriever?.release()
        localScrubRetriever = null
    }

    private fun formatTime(seconds: Float): String = formatPlaybackTime(seconds, totalDuration)

    private fun updateTooltipPosition(progress: Int) {
        if (totalDuration <= 0f || !::scrubTooltip.isInitialized || !::seekBar.isInitialized) return
        val targetSec = (progress.toFloat() / SEEK_BAR_MAX) * totalDuration
        scrubTooltip.text = formatTime(targetSec)

        scrubTooltip.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val tooltipWidth = if (scrubTooltip.width > 0) scrubTooltip.width else scrubTooltip.measuredWidth
        val trackWidth = seekBar.width - seekBar.paddingLeft - seekBar.paddingRight
        if (trackWidth > 0) {
            val thumbX = seekBar.paddingLeft + (progress.toFloat() / SEEK_BAR_MAX) * trackWidth
            val targetX = thumbX - (tooltipWidth / 2f)
            val maxX = (seekBar.width - tooltipWidth).toFloat().coerceAtLeast(0f)
            scrubTooltip.translationX = targetX.coerceIn(0f, maxX)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter =
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
                addAction(android.content.Intent.ACTION_TIME_TICK)
            }
        hudReceiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    ctx: android.content.Context,
                    intent: android.content.Intent,
                ) {
                    if (intent.action == android.content.Intent.ACTION_BATTERY_CHANGED) {
                        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                        val isCharging =
                            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == android.os.BatteryManager.BATTERY_STATUS_FULL

                        if (level >= 0 && scale > 0) {
                            val pct = (level * 100) / scale
                            lastKnownBatteryPercent = pct
                            lastKnownIsCharging = isCharging
                            if (::batteryLabel.isInitialized) batteryLabel.text = "$pct%"

                            val iconRes =
                                when {
                                    isCharging -> R.drawable.icon_battery_charging
                                    pct > 80 -> R.drawable.icon_battery_full
                                    pct > 40 -> R.drawable.icon_battery_medium
                                    pct > 15 -> R.drawable.icon_battery_low
                                    else -> R.drawable.icon_battery_empty
                                }
                            if (::batteryIcon.isInitialized) batteryIcon.setImageResource(iconRes)

                            val color =
                                if (!isCharging && pct <= 15) {
                                    android.graphics.Color.parseColor("#FF4444")
                                } else {
                                    VoidTheme.colorText
                                }
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
        activity.playlistQueueManager.addListener(playlistListener)
        updatePlaylistVisibility()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hudReceiver?.let { context.unregisterReceiver(it) }
        activity.playlistQueueManager.removeListener(playlistListener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val overlay =
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }

        val minButtonPx = VoidTheme.dpToPx(context, MIN_INTERACTIVE_BUTTON_DP)
        val primaryButtonPx = VoidTheme.dpToPx(context, PRIMARY_INTERACTIVE_BUTTON_DP)
        val secondaryIconPx = minButtonPx

        // Popup Container: placed right above the player bar
        popupContainer =
            FrameLayout(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        VoidTheme.dpToPx(context, 540f),
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                    )
                visibility = View.GONE
                isClickable = true
                setOnClickListener { dismissOpenPopup() }
            }
        overlay.addView(popupContainer)

        // Single clean player bar (matching Figma node #122:673)
        val barPanel =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                clipChildren = false
                clipToPadding = false
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorBackground)
                        cornerRadius = VoidTheme.dp(context, 20f)
                        setStroke(VoidTheme.dpToPx(context, 1.5f), VoidTheme.colorBorder)
                    }
                val padH = VoidTheme.dpToPx(context, 28f)
                val padV = VoidTheme.dpToPx(context, 16f)
                setPadding(padH, padV, padH, padV)
                layoutParams =
                    FrameLayout.LayoutParams(
                        VoidTheme.dpToPx(context, 1280f),
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 16f)
                    }
            }

        // --- Row 1: Top (Stop, Title, Battery & Clock) ---
        val topRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 8f)
                    }
            }

        val btnClose =
            VoidIconButton(
                context,
                R.drawable.icon_x,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                val closeSize = VoidTheme.dpToPx(context, 56f)
                minimumWidth = closeSize
                minimumHeight = closeSize
                layoutParams = LinearLayout.LayoutParams(closeSize, closeSize)
                contentDescription = context.getString(R.string.player_btn_stop)
                setOnClickListener { activity.stopPlayback() }
            }
        topRow.addView(btnClose)

        titleLabel =
            TextView(context).apply {
                text = ""
                typeface = VoidTheme.typefaceTitle
                textSize = 22f
                setTextColor(VoidTheme.colorText)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(VoidTheme.dpToPx(context, 14f), 0, VoidTheme.dpToPx(context, 14f), 0)
                    }
            }
        topRow.addView(titleLabel)

        val statusBadge =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurface)
                        cornerRadius = VoidTheme.dp(context, 16f)
                    }
                val padH = VoidTheme.dpToPx(context, 14f)
                val padV = VoidTheme.dpToPx(context, 8f)
                setPadding(padH, padV, padH, padV)
            }

        batteryIcon =
            ImageView(context).apply {
                setImageResource(R.drawable.icon_battery_full)
                setColorFilter(VoidTheme.colorText)
                val iconSize = VoidTheme.dpToPx(context, 28f)
                layoutParams =
                    LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        rightMargin = VoidTheme.dpToPx(context, 8f)
                    }
            }
        statusBadge.addView(batteryIcon)

        batteryLabel =
            TextView(context).apply {
                text = "100%"
                typeface = VoidTheme.typefaceMono
                textSize = 18f
                setTextColor(VoidTheme.colorText)
                setPadding(0, 0, VoidTheme.dpToPx(context, 14f), 0)
            }
        statusBadge.addView(batteryLabel)

        val clockIcon =
            ImageView(context).apply {
                setImageResource(R.drawable.icon_clock)
                setColorFilter(VoidTheme.colorText)
                val iconSize = VoidTheme.dpToPx(context, 28f)
                layoutParams =
                    LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        rightMargin = VoidTheme.dpToPx(context, 8f)
                    }
            }
        statusBadge.addView(clockIcon)

        clockLabel =
            TextView(context).apply {
                text = "12:00"
                typeface = VoidTheme.typefaceMono
                textSize = 18f
                setTextColor(VoidTheme.colorText)
            }
        statusBadge.addView(clockLabel)

        thermalIcon =
            ImageView(context).apply {
                setImageResource(R.drawable.icon_thermal)
                setColorFilter(VoidTheme.colorText)
                val iconSize = VoidTheme.dpToPx(context, 28f)
                layoutParams =
                    LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        leftMargin = VoidTheme.dpToPx(context, 14f)
                        rightMargin = VoidTheme.dpToPx(context, 8f)
                    }
                visibility = View.GONE
            }
        statusBadge.addView(thermalIcon)

        thermalLabel =
            TextView(context).apply {
                text = ""
                typeface = VoidTheme.typefaceMono
                textSize = 18f
                setTextColor(VoidTheme.colorText)
                visibility = View.GONE
            }
        statusBadge.addView(thermalLabel)

        qualityBadge =
            TextView(context).apply {
                text = ""
                typeface = VoidTheme.typefaceMono
                textSize = 16f
                setTextColor(VoidTheme.colorTextSecondary)
                visibility = View.GONE
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = VoidTheme.dpToPx(context, 12f)
                    }
            }
        statusBadge.addView(qualityBadge)

        topRow.addView(statusBadge)
        barPanel.addView(topRow)

        // --- Row 2: Timeline (Seek) ---
        val timelineRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 12f)
                    }
            }

        timeLabel =
            TextView(context).apply {
                text = "00:00"
                typeface = VoidTheme.typefaceMono
                textSize = 20f
                setTextColor(VoidTheme.colorText)
            }
        timelineRow.addView(timeLabel)

        val seekBarContainer =
            FrameLayout(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                        setMargins(VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 16f), 0)
                    }
                clipChildren = false
                clipToPadding = false
            }

        scrubTooltip =
            TextView(context).apply {
                typeface = VoidTheme.typefaceMono
                textSize = 20f
                setTextColor(VoidTheme.colorText)
                gravity = Gravity.CENTER
                val padH = VoidTheme.dpToPx(context, 10f)
                val padV = VoidTheme.dpToPx(context, 4f)
                setPadding(padH, padV, padH, padV)
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        cornerRadius = VoidTheme.dp(context, 6f)
                        setStroke(VoidTheme.dpToPx(context, 1.5f), VoidTheme.colorAccent)
                    }
                visibility = View.GONE
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START,
                    )
                translationY = -VoidTheme.dpToPx(context, 38f).toFloat()
            }
        seekBarContainer.addView(scrubTooltip)

        seekBar =
            SeekBar(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL,
                    )
                progressTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
                thumbTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
                max = SEEK_BAR_MAX
                progress = 0
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            p0: SeekBar?,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            if (fromUser && totalDuration > 0) {
                                val target = (progress.toFloat() / SEEK_BAR_MAX) * totalDuration
                                timeLabel.text = formatTime(target)
                                updateTooltipPosition(progress)
                                updateScrubPreview(target)
                            }
                        }

                        override fun onStartTrackingTouch(p0: SeekBar?) {
                            isDragging = true
                            scrubTooltip.visibility = View.VISIBLE
                            updateTooltipPosition(progress)
                            startScrubPreview()
                        }

                        override fun onStopTrackingTouch(p0: SeekBar?) {
                            isDragging = false
                            scrubTooltip.visibility = View.GONE
                            stopScrubPreview()
                            lastSeekTime = System.currentTimeMillis()
                            if (totalDuration > 0) {
                                val target = (progress.toFloat() / SEEK_BAR_MAX) * totalDuration
                                timeLabel.text = formatTime(target)
                                activity.nativeSeekVideo(target)
                            }
                        }
                    },
                )
            }
        seekBarContainer.addView(seekBar)
        timelineRow.addView(seekBarContainer)

        totalTimeLabel =
            TextView(context).apply {
                text = "00:00"
                typeface = VoidTheme.typefaceMono
                textSize = 20f
                setTextColor(VoidTheme.colorText)
            }
        timelineRow.addView(totalTimeLabel)
        barPanel.addView(timelineRow)

        // --- Row 3: Buttons (Left, Center, Right) ---
        val controlsRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        // Left Group: 3D format, Screen, Environment, Queue
        val leftGroup =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        btnFormat =
            VoidIconButton(
                context,
                R.drawable.icon_3d_sbs,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams = LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx)
                contentDescription = context.getString(R.string.player_btn_format)
                setOnClickListener { toggleMenu(ControlsMenuType.FORMAT_3D) }
            }
        leftGroup.addView(btnFormat)

        btnScreen =
            VoidIconButton(
                context,
                R.drawable.icon_2d,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams =
                    LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx).apply {
                        leftMargin = VoidTheme.dpToPx(context, 10f)
                    }
                contentDescription = context.getString(R.string.player_btn_screen)
                setOnClickListener { toggleMenu(ControlsMenuType.SCREEN) }
            }
        leftGroup.addView(btnScreen)

        btnEnvironment =
            VoidIconButton(
                context,
                R.drawable.icon_environment,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams =
                    LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx).apply {
                        leftMargin = VoidTheme.dpToPx(context, 10f)
                    }
                contentDescription = context.getString(R.string.player_btn_environment)
                setOnClickListener { toggleMenu(ControlsMenuType.ENVIRONMENT) }
            }
        leftGroup.addView(btnEnvironment)

        btnQueue =
            VoidIconButton(
                context,
                R.drawable.ic_view_list,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams =
                    LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx).apply {
                        leftMargin = VoidTheme.dpToPx(context, 10f)
                    }
                contentDescription = context.getString(R.string.player_btn_playlist)
                visibility = if (activity.playlistQueueManager.currentPlaylist != null) View.VISIBLE else View.GONE
                setOnClickListener { toggleMenu(ControlsMenuType.PLAYLIST) }
            }
        leftGroup.addView(btnQueue)

        controlsRow.addView(leftGroup)

        // Flexible spacer between left and center
        controlsRow.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f)
            },
        )

        // Center Group: Previous, -10s, Play/Pause (96px), +10s, Next
        val centerGroup =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        btnPrevious =
            VoidIconButton(
                context,
                R.drawable.icon_skip_back,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams =
                    LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx).apply {
                        rightMargin = VoidTheme.dpToPx(context, 8f)
                    }
                contentDescription = context.getString(R.string.player_btn_previous)
                visibility = if (activity.playlistQueueManager.currentPlaylist != null) View.VISIBLE else View.GONE
                setOnClickListener { activity.playlistQueueManager.playPrevious() }
            }
        centerGroup.addView(btnPrevious)

        val btnRewind =
            VoidIconButton(
                context,
                R.drawable.icon_skip_back,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams = LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx)
                contentDescription = context.getString(R.string.player_btn_rewind)
                setOnClickListener {
                    val newTarget = kotlin.math.max(0f, lastKnownCurrentSec - 10f)
                    activity.nativeSeekVideo(newTarget)
                }
            }
        centerGroup.addView(btnRewind)

        btnPlayPause =
            VoidIconButton(
                context,
                R.drawable.icon_pause,
                VoidButtonStyle.PRIMARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = primaryButtonPx
                minimumHeight = primaryButtonPx
                layoutParams =
                    LinearLayout.LayoutParams(primaryButtonPx, primaryButtonPx).apply {
                        leftMargin = VoidTheme.dpToPx(context, 16f)
                        rightMargin = VoidTheme.dpToPx(context, 16f)
                    }
                contentDescription = context.getString(R.string.player_btn_play_pause)
                setOnClickListener { onPlayPause() }
            }
        centerGroup.addView(btnPlayPause)

        val btnForward =
            VoidIconButton(
                context,
                R.drawable.icon_skip_forward,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams = LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx)
                contentDescription = context.getString(R.string.player_btn_forward)
                setOnClickListener {
                    val newTarget = kotlin.math.min(totalDuration, lastKnownCurrentSec + 10f)
                    activity.nativeSeekVideo(newTarget)
                }
            }
        centerGroup.addView(btnForward)

        btnNext =
            VoidIconButton(
                context,
                R.drawable.icon_skip_forward,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams =
                    LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx).apply {
                        leftMargin = VoidTheme.dpToPx(context, 8f)
                    }
                contentDescription = context.getString(R.string.player_btn_next)
                visibility = if (activity.playlistQueueManager.currentPlaylist != null) View.VISIBLE else View.GONE
                setOnClickListener { activity.playlistQueueManager.playNext() }
            }
        centerGroup.addView(btnNext)

        controlsRow.addView(centerGroup)

        // Flexible spacer between center and right
        controlsRow.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f)
            },
        )

        // Right Group: Subtitles, Audio, Speed, Volume, Stats
        val rightGroup =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        btnSubtitles =
            VoidIconButton(
                context,
                R.drawable.icon_subtitles,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams = LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx)
                contentDescription = context.getString(R.string.player_btn_subtitles)
                setOnClickListener { toggleMenu(ControlsMenuType.SUBTITLES) }
            }
        rightGroup.addView(btnSubtitles)

        btnAudio =
            VoidIconButton(
                context,
                R.drawable.ic_audio,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams =
                    LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx).apply {
                        leftMargin = VoidTheme.dpToPx(context, 10f)
                    }
                contentDescription = context.getString(R.string.player_btn_audio)
                setOnClickListener { toggleMenu(ControlsMenuType.AUDIO) }
            }
        rightGroup.addView(btnAudio)

        btnSpeed =
            TextView(context).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams =
                    LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx).apply {
                        leftMargin = VoidTheme.dpToPx(context, 10f)
                    }
                gravity = Gravity.CENTER
                typeface = VoidTheme.typefaceMono
                textSize = 18f
                setTextColor(VoidTheme.colorText)
                text = formatSpeedText(currentSpeed)
                contentDescription = context.getString(R.string.player_btn_speed)
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurface)
                        cornerRadius = VoidTheme.dp(context, 36f)
                        setStroke(VoidTheme.dpToPx(context, 1.5f), VoidTheme.colorBorder)
                    }
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleMenu(ControlsMenuType.SPEED) }
            }
        rightGroup.addView(btnSpeed)

        btnVolume =
            VoidIconButton(
                context,
                R.drawable.icon_volume_2,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams =
                    LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx).apply {
                        leftMargin = VoidTheme.dpToPx(context, 10f)
                    }
                contentDescription = context.getString(R.string.player_btn_volume)
                setOnClickListener { toggleMenu(ControlsMenuType.VOLUME) }
            }
        rightGroup.addView(btnVolume)

        btnDebugStats =
            VoidIconButton(
                context,
                R.drawable.icon_stats,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                minimumWidth = secondaryIconPx
                minimumHeight = secondaryIconPx
                layoutParams =
                    LinearLayout.LayoutParams(secondaryIconPx, secondaryIconPx).apply {
                        leftMargin = VoidTheme.dpToPx(context, 10f)
                    }
                contentDescription = context.getString(R.string.player_btn_debug_stats)
                visibility = if (activity.isDebugStatsEnabled) View.VISIBLE else View.GONE
                setOnClickListener { activity.cycleDebugStatsMode() }
            }
        rightGroup.addView(btnDebugStats)

        controlsRow.addView(rightGroup)
        barPanel.addView(controlsRow)

        overlay.addView(barPanel)

        loadingSpinner =
            ProgressBar(context).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(VoidTheme.colorAccent)
                visibility = View.GONE
            }
        overlay.addView(
            loadingSpinner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        scrubPreview =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(VoidTheme.colorSurface)
                visibility = View.GONE
            }
        overlay.addView(
            scrubPreview,
            FrameLayout.LayoutParams(
                VoidTheme.dpToPx(context, 160f),
                VoidTheme.dpToPx(context, 90f),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 8f)
            },
        )

        setContentView(overlay)
        window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    fun updatePassthroughState(enabled: Boolean) {
        val passthroughSupported = activity.nativeIsPassthroughSupported()
        btnPassthrough?.style =
            when {
                !passthroughSupported -> VoidButtonStyle.DISABLED
                enabled -> VoidButtonStyle.ACTIVE
                else -> VoidButtonStyle.SECONDARY
            }
    }
}
