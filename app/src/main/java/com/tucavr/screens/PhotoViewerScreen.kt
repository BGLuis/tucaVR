package com.tucavr.screens

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidFilterChip
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.photos.DecodedPhoto
import com.tucavr.photos.PhotoDecoder
import com.tucavr.photos.PhotoFormat
import com.tucavr.photos.PhotoProjection
import com.tucavr.photos.PhotoStereoMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Visualizador imersivo de fotos 360° e 3D estereoscópicas para Meta Quest 3 (T8.5).
 * Controla navegação entre fotos da pasta, zoom, pan, slideshow automático
 * e alternância manual de projeções (Flat, 360°, 180°, SBS, OU).
 */
class PhotoViewerScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit
) {

    private var photoList: List<MediaEntry> = emptyList()
    private var currentIndex: Int = 0
    private var currentPhoto: DecodedPhoto? = null
    private var currentZoom: Float = 1.0f
    private var currentPanX: Float = 0.0f
    private var currentPanY: Float = 0.0f
    private var isSlideshowRunning: Boolean = false
    private var slideshowIntervalSec: Int = 5
    private var slideshowJob: Job? = null
    private var loadingJob: Job? = null

    // UI elements
    private var counterView: TextView? = null
    private var titleHeaderView: TextView? = null
    private var dimensionsLabel: TextView? = null
    private var projectionLabel: TextView? = null
    private var zoomLabel: TextView? = null
    private var loadingSpinner: ProgressBar? = null
    private var slideshowButton: VoidButton? = null
    private var prevButton: VoidButton? = null
    private var nextButton: VoidButton? = null

    /**
     * Inicializa o visualizador com a lista de fotos da pasta e o índice inicial.
     */
    fun render(initialEntry: MediaEntry, entries: List<MediaEntry>, initialIndex: Int = 0) {
        photoList = if (entries.isNotEmpty()) entries else listOf(initialEntry)
        currentIndex = if (initialIndex in photoList.indices) {
            initialIndex
        } else {
            photoList.indexOfFirst { it.path == initialEntry.path }.coerceAtLeast(0)
        }
        currentZoom = 1.0f
        currentPanX = 0.0f
        currentPanY = 0.0f
        isSlideshowRunning = false
        slideshowJob?.cancel()

        activity.setPhotoZoom(currentZoom)
        activity.setPhotoPan(currentPanX, currentPanY)

        val root = VoidPanelChrome.newRoot(context)

        // Header com botão voltar
        val header = VoidPanelChrome.buildHeader(
            context,
            title = context.getString(R.string.photo_viewer_title),
            subtitle = photoList.getOrNull(currentIndex)?.name ?: "",
            onBack = { exitViewer() }
        )
        titleHeaderView = header.findViewById(VoidPanelChrome.ID_SUBTITLE)
        root.addView(header)

        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = VoidTheme.dpToPx(context, 16f)
            setPadding(pad, pad, pad, pad)
        }
        scroller.addView(content)
        root.addView(scroller)

        // 1. Barra de Navegação de Fotos (Anterior / Índice / Próxima)
        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 14f)
            }
        }

        prevButton = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = "◀  " + context.getString(R.string.photo_viewer_prev)
            minHeight = VoidTheme.dpToPx(context, 48f)
            setOnClickListener { showPreviousPhoto() }
        }
        navRow.addView(prevButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        counterView = VoidText.mono(
            context,
            context.getString(R.string.photo_viewer_index_format, currentIndex + 1, photoList.size),
            sizeSp = 16f
        ).apply {
            gravity = Gravity.CENTER
            setPadding(VoidTheme.dpToPx(context, 12f), 0, VoidTheme.dpToPx(context, 12f), 0)
        }
        navRow.addView(counterView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        nextButton = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.photo_viewer_next) + "  ▶"
            minHeight = VoidTheme.dpToPx(context, 48f)
            setOnClickListener { showNextPhoto() }
        }
        navRow.addView(nextButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(navRow)

        // 2. Loading Spinner
        loadingSpinner = ProgressBar(context).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }
        content.addView(loadingSpinner)

        // 3. Informações da Imagem e Projeção (Card de Metadados)
        val infoCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, 12f)
                setStroke(1, Color.parseColor("#33FFFFFF"))
            }
            val padInner = VoidTheme.dpToPx(context, 14f)
            setPadding(padInner, padInner, padInner, padInner)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 16f)
            }
        }

        dimensionsLabel = VoidText.body(context, context.getString(R.string.photo_viewer_dimensions_format, 0, 0), sizeSp = 14f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
        }
        infoCard.addView(dimensionsLabel)

        projectionLabel = VoidText.body(context, "", sizeSp = 14f, secondary = true)
        infoCard.addView(projectionLabel)
        content.addView(infoCard)

        // 4. Seção de Controles de Zoom
        content.addView(VoidText.title(context, "Zoom & Escala", sizeSp = 15f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        })

        val zoomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 16f)
            }
        }

        zoomRow.addView(VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.photo_viewer_zoom_out)
            minHeight = VoidTheme.dpToPx(context, 44f)
            setOnClickListener { adjustZoom(-0.25f) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = VoidTheme.dpToPx(context, 8f)
        })

        zoomLabel = VoidText.mono(context, "1.00x", sizeSp = 15f).apply {
            gravity = Gravity.CENTER
            minWidth = VoidTheme.dpToPx(context, 64f)
        }
        zoomRow.addView(zoomLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        zoomRow.addView(VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.photo_viewer_zoom_in)
            minHeight = VoidTheme.dpToPx(context, 44f)
            setOnClickListener { adjustZoom(0.25f) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = VoidTheme.dpToPx(context, 8f)
            marginEnd = VoidTheme.dpToPx(context, 8f)
        })

        zoomRow.addView(VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.photo_viewer_zoom_reset)
            minHeight = VoidTheme.dpToPx(context, 44f)
            setOnClickListener { resetZoom() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(zoomRow)

        // 5. Seção de Slideshow Automático
        content.addView(VoidText.title(context, "Slideshow", sizeSp = 15f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        })

        val slideshowRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 16f)
            }
        }

        slideshowButton = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.photo_viewer_slideshow_start)
            minHeight = VoidTheme.dpToPx(context, 46f)
            setOnClickListener { toggleSlideshow() }
        }
        slideshowRow.addView(slideshowButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply {
            marginEnd = VoidTheme.dpToPx(context, 10f)
        })

        // Chips de intervalo de tempo
        listOf(3, 5, 10, 15).forEach { sec ->
            val chip = VoidFilterChip(context, context.getString(R.string.photo_viewer_slideshow_interval_format, sec), isSelectedChip = slideshowIntervalSec == sec).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.marginEnd = VoidTheme.dpToPx(context, 6f)
                }
                setOnClickListener {
                    slideshowIntervalSec = sec
                    updateSlideshowChips(slideshowRow)
                }
            }
            slideshowRow.addView(chip)
        }
        content.addView(slideshowRow)

        // 6. Seção de Formato & Projeção Manual
        content.addView(VoidText.title(context, "Formato Manual", sizeSp = 15f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        })

        val formatChipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 12f)
            }
        }

        listOf(
            "2D" to PhotoFormat(PhotoProjection.FLAT, PhotoStereoMode.MONO),
            "360°" to PhotoFormat(PhotoProjection.EQUIRECTANGULAR_360, PhotoStereoMode.MONO),
            "180°" to PhotoFormat(PhotoProjection.VR_180, PhotoStereoMode.MONO),
            "3D SBS" to PhotoFormat(PhotoProjection.FLAT, PhotoStereoMode.SIDE_BY_SIDE),
            "3D OU" to PhotoFormat(PhotoProjection.FLAT, PhotoStereoMode.OVER_UNDER)
        ).forEach { (label, fmt) ->
            val chip = VoidFilterChip(context, label, isSelectedChip = false).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.marginEnd = VoidTheme.dpToPx(context, 8f)
                }
                setOnClickListener {
                    applyManualFormat(fmt)
                }
            }
            formatChipsRow.addView(chip)
        }
        content.addView(formatChipsRow)

        host.showScreen(root)
        loadCurrentPhoto()
    }

    private fun loadCurrentPhoto() {
        val entry = photoList.getOrNull(currentIndex) ?: return
        titleHeaderView?.text = entry.name
        counterView?.text = context.getString(R.string.photo_viewer_index_format, currentIndex + 1, photoList.size)
        loadingSpinner?.visibility = View.VISIBLE

        loadingJob?.cancel()
        loadingJob = scope.launch {
            val photo = withContext(Dispatchers.IO) {
                PhotoDecoder.decodePhoto(entry.path)
            }

            loadingSpinner?.visibility = View.GONE

            if (photo != null) {
                currentPhoto = photo
                val rgba = photo.toRgbaByteArray()
                val screenMode = photo.format.toScreenMode()

                activity.loadPhoto(rgba, photo.width, photo.height, screenMode)

                // Atualizar labels informativos
                dimensionsLabel?.text = context.getString(
                    R.string.photo_viewer_dimensions_format, photo.originalWidth, photo.originalHeight
                ) + if (photo.width != photo.originalWidth) " (downscale ${photo.width}×${photo.height})" else ""

                val projLabel = when (photo.format.projection) {
                    PhotoProjection.EQUIRECTANGULAR_360 -> context.getString(R.string.photo_viewer_projection_360)
                    PhotoProjection.VR_180 -> context.getString(R.string.photo_viewer_projection_180)
                    PhotoProjection.FLAT -> context.getString(R.string.photo_viewer_projection_flat)
                }
                val stereoLabel = when (photo.format.stereoMode) {
                    PhotoStereoMode.SIDE_BY_SIDE -> " · " + context.getString(R.string.photo_viewer_stereo_sbs)
                    PhotoStereoMode.OVER_UNDER -> " · " + context.getString(R.string.photo_viewer_stereo_ou)
                    PhotoStereoMode.MONO -> ""
                }
                projectionLabel?.text = projLabel + stereoLabel
            } else {
                projectionLabel?.text = context.getString(R.string.photo_viewer_error)
            }
        }
    }

    private fun showNextPhoto() {
        if (photoList.isEmpty()) return
        currentIndex = (currentIndex + 1) % photoList.size
        loadCurrentPhoto()
    }

    private fun showPreviousPhoto() {
        if (photoList.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) photoList.size - 1 else currentIndex - 1
        loadCurrentPhoto()
    }

    private fun adjustZoom(delta: Float) {
        currentZoom = (currentZoom + delta).coerceIn(0.5f, 4.0f)
        zoomLabel?.text = String.format(java.util.Locale.US, "%.2fx", currentZoom)
        activity.setPhotoZoom(currentZoom)
    }

    private fun resetZoom() {
        currentZoom = 1.0f
        currentPanX = 0.0f
        currentPanY = 0.0f
        zoomLabel?.text = "1.00x"
        activity.setPhotoZoom(1.0f)
        activity.setPhotoPan(0.0f, 0.0f)
    }

    private fun toggleSlideshow() {
        isSlideshowRunning = !isSlideshowRunning
        if (isSlideshowRunning) {
            slideshowButton?.text = context.getString(R.string.photo_viewer_slideshow_stop)
            startSlideshow()
        } else {
            slideshowButton?.text = context.getString(R.string.photo_viewer_slideshow_start)
            slideshowJob?.cancel()
        }
    }

    private fun startSlideshow() {
        slideshowJob?.cancel()
        slideshowJob = scope.launch {
            while (isActive && isSlideshowRunning) {
                delay(slideshowIntervalSec * 1000L)
                if (!isActive || !isSlideshowRunning) break
                showNextPhoto()
            }
        }
    }

    private fun updateSlideshowChips(row: LinearLayout) {
        for (i in 1 until row.childCount) {
            val child = row.getChildAt(i)
            if (child is VoidFilterChip) {
                val secStr = child.text.toString().filter { it.isDigit() }
                val sec = secStr.toIntOrNull() ?: 5
                child.setSelectedState(sec == slideshowIntervalSec)
            }
        }
        if (isSlideshowRunning) {
            startSlideshow()
        }
    }

    private fun applyManualFormat(format: PhotoFormat) {
        val photo = currentPhoto ?: return
        val updatedPhoto = photo.copy(format = format)
        currentPhoto = updatedPhoto
        val rgba = updatedPhoto.toRgbaByteArray()
        activity.loadPhoto(rgba, updatedPhoto.width, updatedPhoto.height, format.toScreenMode())

        val projLabel = when (format.projection) {
            PhotoProjection.EQUIRECTANGULAR_360 -> context.getString(R.string.photo_viewer_projection_360)
            PhotoProjection.VR_180 -> context.getString(R.string.photo_viewer_projection_180)
            PhotoProjection.FLAT -> context.getString(R.string.photo_viewer_projection_flat)
        }
        val stereoLabel = when (format.stereoMode) {
            PhotoStereoMode.SIDE_BY_SIDE -> " · " + context.getString(R.string.photo_viewer_stereo_sbs)
            PhotoStereoMode.OVER_UNDER -> " · " + context.getString(R.string.photo_viewer_stereo_ou)
            PhotoStereoMode.MONO -> ""
        }
        projectionLabel?.text = projLabel + stereoLabel
    }

    /**
     * Encerra a visualização, descarrega a textura estática da GPU e retorna à tela anterior.
     */
    fun exitViewer() {
        isSlideshowRunning = false
        slideshowJob?.cancel()
        loadingJob?.cancel()
        activity.clearPhoto()
        onBack()
    }
}
