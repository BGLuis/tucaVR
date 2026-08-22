package com.vrplayer.screens

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vrplayer.R
import com.vrplayer.VRActivity
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.filebrowser.MediaEntry
import com.vrplayer.filebrowser.MediaMetadata
import com.vrplayer.filebrowser.MediaMetadataReader
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.NetworkThumbnailGenerator
import com.vrplayer.filebrowser.ThumbnailGenerator
import com.vrplayer.filebrowser.TrackInfo
import com.vrplayer.history.formatDurationMs
import com.vrplayer.navigation.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val TAG_LABEL_RES = mapOf(
    "title" to R.string.file_detail_tag_title,
    "artist" to R.string.file_detail_tag_artist,
    "album" to R.string.file_detail_tag_album,
    "date" to R.string.file_detail_tag_date,
    "genre" to R.string.file_detail_tag_genre,
    "comment" to R.string.file_detail_tag_comment
)

private val MODE_LABEL_RES_IDS = intArrayOf(
    R.string.player_mode_2d,
    R.string.player_mode_sbs,
    R.string.player_mode_sbs_half,
    R.string.player_mode_ou,
    R.string.player_mode_ou_half,
    R.string.player_mode_360,
    R.string.player_mode_180,
    R.string.player_mode_360_sbs,
    R.string.player_mode_360_ou,
    R.string.player_mode_180_sbs
)

/**
 * Tela unificada "Tocando Agora" (Now Playing Screen).
 * Combina informações detalhadas da mídia, metadados técnicos do container, seleção dinâmica de trilhas
 * de áudio/legendas em tempo real e atalhos para controle de reprodução.
 */
class PlayerScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit
) {

    private var activeAudioOrdinal = 0

    fun render(source: PlaybackSource) {
        val root = VoidPanelChrome.newRoot(context)
        val displayName = resolveDisplayName(source)
        val subtitle = resolveSubtitle(source)

        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.player_now_playing_title),
                subtitle = displayName,
                onBack = { onBack() }
            )
        )

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroller)

        // Banner Hero com Thumbnail
        val thumbnailView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, VoidTheme.dpToPx(context, 180f)
            ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, 10f)
            }
            clipToOutline = true
        }
        content.addView(thumbnailView)

        // Dica de controles em VR
        content.addView(VoidText.body(
            context,
            context.getString(R.string.player_label_controls_hint),
            sizeSp = 14f,
            secondary = true
        ).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 12f))
        })

        // Seção: Arquivo
        val fileSection = sectionContainer(content, R.string.file_detail_section_file)
        val sizeBytes = sizeBytesFor(source)
        if (sizeBytes > 0L) {
            addRow(fileSection, context.getString(R.string.file_detail_label_size), formatFileSize(context, sizeBytes))
        }
        addRow(fileSection, context.getString(R.string.file_detail_label_path), pathFor(source))

        // Seção: Mídia
        val mediaSection = sectionContainer(content, R.string.file_detail_section_media)
        val mediaLoading = VoidText.body(context, context.getString(R.string.file_detail_metadata_loading), sizeSp = 15f, secondary = true)
        mediaSection.addView(mediaLoading)

        // Seção: Tags
        val tagsSectionTitle = sectionTitle(context.getString(R.string.file_detail_section_tags))
        val tagsSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        tagsSectionTitle.visibility = View.GONE
        tagsSection.visibility = View.GONE
        content.addView(tagsSectionTitle)
        content.addView(tagsSection)

        // Seção: Trilhas
        val tracksSectionTitle = sectionTitle(context.getString(R.string.file_detail_section_tracks))
        val tracksSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(tracksSectionTitle)
        content.addView(tracksSection)

        host.showScreen(root)

        // Carrega miniatura da mídia
        scope.launch {
            val bitmap = when (source) {
                is PlaybackSource.LocalFile -> ThumbnailGenerator.getThumbnail(
                    context,
                    MediaEntry(
                        name = source.path.substringAfterLast('/'),
                        path = source.path,
                        sizeBytes = source.sizeBytes,
                        lastModified = 0L,
                        type = MediaType.VIDEO
                    )
                )
                else -> NetworkThumbnailGenerator.getThumbnail(context, activity, source)
            }
            if (bitmap != null) {
                thumbnailView.setImageBitmap(bitmap)
            }
        }

        // Carrega metadados e lista de trilhas
        scope.launch {
            val metadata = MediaMetadataReader.read(activity, source)
            mediaSection.removeView(mediaLoading)

            if (metadata == null) {
                mediaSection.addView(VoidText.body(
                    context, context.getString(R.string.file_detail_metadata_error), sizeSp = 15f, secondary = true
                ))
                return@launch
            }

            populateMediaSection(mediaSection, metadata)
            populateTagsSection(tagsSection, tagsSectionTitle, metadata)
            populateTracksSection(tracksSection, metadata)
        }
    }

    private fun populateMediaSection(section: LinearLayout, meta: MediaMetadata) {
        val containerLabel = if (meta.containerLong.isNotEmpty()) meta.containerLong else meta.container
        if (containerLabel.isNotEmpty()) {
            addRow(section, context.getString(R.string.file_detail_label_container), containerLabel)
        }
        if (meta.durationMs > 0L) {
            addRow(section, context.getString(R.string.file_detail_label_duration), formatDurationMs(meta.durationMs))
        }
        meta.videoTracks.firstOrNull()?.let { v ->
            if (v.codec.isNotEmpty()) {
                addRow(section, context.getString(R.string.file_detail_label_video_codec), v.codec)
            }
            if (v.width > 0 && v.height > 0) {
                val fpsStr = if (v.fpsMilli > 0) " @ %.2f fps".format(v.fpsMilli / 1000f) else ""
                addRow(section, context.getString(R.string.file_detail_label_resolution), "${v.width}x${v.height}$fpsStr")
            }
        }
        meta.audioTracks.firstOrNull()?.let { a ->
            if (a.codec.isNotEmpty()) {
                val chStr = if (a.channels > 0) " (${a.channels} ch)" else ""
                addRow(section, context.getString(R.string.file_detail_label_audio_codec), "${a.codec}$chStr")
            }
        }
        if (meta.bitRate > 0L) {
            val mbps = meta.bitRate.toDouble() / 1_000_000.0
            addRow(section, context.getString(R.string.file_detail_label_bitrate), context.getString(R.string.file_detail_value_bitrate_format, mbps))
        }
        if (meta.format3dIndex in MODE_LABEL_RES_IDS.indices) {
            val modeLabel = context.getString(MODE_LABEL_RES_IDS[meta.format3dIndex])
            val suffix = if (meta.detectionConfidence < 3) context.getString(R.string.file_detail_format3d_low_confidence_suffix) else ""
            addRow(section, context.getString(R.string.file_detail_label_format3d), modeLabel + suffix)
        }
    }

    private fun populateTagsSection(section: LinearLayout, titleView: View, meta: MediaMetadata) {
        if (meta.tags.isEmpty()) return
        titleView.visibility = View.VISIBLE
        section.visibility = View.VISIBLE

        meta.tags.forEach { (key, value) ->
            val labelRes = TAG_LABEL_RES[key.lowercase()]
            val label = if (labelRes != null) context.getString(labelRes) else key
            addRow(section, label, value)
        }
    }

    private fun populateTracksSection(section: LinearLayout, meta: MediaMetadata) {
        if (meta.tracks.isEmpty()) {
            section.addView(VoidText.body(context, context.getString(R.string.file_detail_tracks_empty), sizeSp = 15f, secondary = true))
            return
        }

        meta.tracks.forEach { track ->
            val isAudio = track.kind == com.vrplayer.filebrowser.TrackKind.AUDIO
            val isSubtitle = track.kind == com.vrplayer.filebrowser.TrackKind.SUBTITLE

            val kindLabel = when (track.kind) {
                com.vrplayer.filebrowser.TrackKind.VIDEO -> context.getString(R.string.file_detail_track_video_format, track.ordinal, track.codec)
                com.vrplayer.filebrowser.TrackKind.AUDIO -> context.getString(R.string.file_detail_track_audio_format, track.ordinal, track.codec)
                com.vrplayer.filebrowser.TrackKind.SUBTITLE -> context.getString(R.string.file_detail_track_subtitle_format, track.ordinal, track.codec)
            }

            val descParts = mutableListOf<String>()
            if (track.language.isNotEmpty()) descParts.add(track.language.uppercase())
            if (track.title.isNotEmpty()) descParts.add(track.title)
            if (track.channels > 0) descParts.add("${track.channels} ch")
            val desc = if (descParts.isNotEmpty()) descParts.joinToString(" · ") else context.getString(R.string.file_detail_track_lang_unknown)

            if (isAudio) {
                val isSelected = track.ordinal == activeAudioOrdinal
                val btn = VoidButton(context, if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY).apply {
                    text = "$kindLabel ($desc)"
                    textSize = 15f
                    minHeight = VoidTheme.dpToPx(context, 48f)
                    val padH = VoidTheme.dpToPx(context, 14f)
                    val padV = VoidTheme.dpToPx(context, 8f)
                    setPadding(padH, padV, padH, padV)
                    setOnClickListener {
                        activeAudioOrdinal = track.ordinal
                        activity.nativeSetAudioTrack(track.ordinal)
                        // Atualiza estilos dos botões da seção de trilhas
                        populateTracksSection(section.apply { removeAllViews() }, meta)
                    }
                }
                section.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.bottomMargin = VoidTheme.dpToPx(context, 6f)
                })
            } else {
                addRow(section, kindLabel, desc)
            }
        }
    }

    private fun sectionContainer(parent: LinearLayout, titleRes: Int): LinearLayout {
        parent.addView(sectionTitle(context.getString(titleRes)))
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(container)
        return container
    }

    private fun sectionTitle(title: String): View =
        VoidText.title(context, title, sizeSp = 18f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 14f), 0, VoidTheme.dpToPx(context, 6f))
        }

    private fun addRow(section: LinearLayout, label: String, value: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, VoidTheme.dpToPx(context, 2f), 0, VoidTheme.dpToPx(context, 2f))
        }
        val labelView = VoidText.mono(context, "$label:", sizeSp = 14f, secondary = true).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 140f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val valueView = VoidText.body(context, value, sizeSp = 14f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(labelView)
        row.addView(valueView)
        section.addView(row)
    }

    private fun resolveDisplayName(source: PlaybackSource): String = when (source) {
        is PlaybackSource.LocalFile -> source.path.substringAfterLast('/')
        is PlaybackSource.Http -> source.url.substringAfterLast('/')
        is PlaybackSource.Smb -> source.path.substringAfterLast('/')
        is PlaybackSource.Ftp -> source.path.substringAfterLast('/')
        is PlaybackSource.Sftp -> source.path.substringAfterLast('/')
        is PlaybackSource.Nfs -> source.path.substringAfterLast('/')
        is PlaybackSource.Dlna -> source.title
    }

    private fun resolveSubtitle(source: PlaybackSource): String = when (source) {
        is PlaybackSource.LocalFile -> context.getString(R.string.file_detail_subtitle_local)
        is PlaybackSource.Http -> context.getString(R.string.file_detail_subtitle_http)
        is PlaybackSource.Smb -> context.getString(R.string.file_detail_subtitle_smb_format, source.server.name)
        is PlaybackSource.Ftp -> context.getString(R.string.file_detail_subtitle_ftp_format, source.server.name)
        is PlaybackSource.Sftp -> context.getString(R.string.file_detail_subtitle_sftp_format, source.server.name)
        is PlaybackSource.Nfs -> "${source.server.name} (${source.server.host})"
        is PlaybackSource.Dlna -> "${source.server.name} (DLNA)"
    }

    private fun pathFor(source: PlaybackSource): String = when (source) {
        is PlaybackSource.LocalFile -> source.path
        is PlaybackSource.Http -> source.url
        is PlaybackSource.Smb -> "${source.server.share}/${source.path}"
        is PlaybackSource.Ftp -> source.path
        is PlaybackSource.Sftp -> source.path
        is PlaybackSource.Nfs -> "${source.server.path}/${source.path}"
        is PlaybackSource.Dlna -> source.url
    }

    private fun sizeBytesFor(source: PlaybackSource): Long = when (source) {
        is PlaybackSource.LocalFile -> source.sizeBytes
        is PlaybackSource.Smb -> source.sizeBytes
        is PlaybackSource.Ftp -> source.sizeBytes
        is PlaybackSource.Sftp -> source.sizeBytes
        is PlaybackSource.Nfs -> source.sizeBytes
        is PlaybackSource.Dlna -> source.sizeBytes
        is PlaybackSource.Http -> 0L
    }
}
