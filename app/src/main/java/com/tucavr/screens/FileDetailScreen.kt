package com.tucavr.screens

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidListRow
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.filebrowser.MediaMetadata
import com.tucavr.filebrowser.MediaMetadataReader
import com.tucavr.filebrowser.MediaType
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ThumbnailGenerator
import com.tucavr.filebrowser.TrackInfo
import com.tucavr.history.formatDurationMs
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Tags do container repassadas pelo Rust (whitelist em rust/core/src/metadata.rs)
// mapeadas pra rotulos localizados -- nao exibimos as chaves cruas do FFmpeg.
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
    R.string.player_mode_180_sbs,
)

/**
 * Tela de detalhe de arquivo (T13.2) -- thumbnail + metadados tecnicos +
 * lista de trilhas, compartilhada entre fonte local e de rede (SMB/FTP/SFTP/
 * HTTP). Substitui o antigo `LocalFilesScreen.renderLocalFileDetail`
 * (so local, sem trilhas).
 *
 * Selecao de trilha de audio: `selectedAudioOrdinal` e estado local desta
 * tela (nao persiste entre aberturas). `desired_audio_track` do lado Rust e
 * estado GLOBAL do controller -- por isso `nativeSetAudioTrack` e chamado
 * SEMPRE ao apertar Reproduzir, inclusive com o ordinal default (0), senao a
 * escolha feita num arquivo vazaria pro proximo (ver rust/bridge/src/lib.rs).
 */
class FileDetailScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit,
    private val onPlay: (PlaybackSource) -> Unit
) {

    fun render(dest: Destination.FileDetail) {
        val source = dest.source
        var selectedAudioOrdinal = 0
        var selectedSubtitleOrdinal = -1

        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = dest.displayName, subtitle = subtitleFor(source), onBack = { onBack() })
        )

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroller)

        val thumbnailView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, VoidTheme.dpToPx(context, 200f)
            ).apply { bottomMargin = VoidTheme.dpToPx(context, 20f) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, 10f)
            }
            clipToOutline = true
        }
        content.addView(thumbnailView)

        val fileSection = sectionContainer(content, R.string.file_detail_section_file)
        addRow(fileSection, context.getString(R.string.file_detail_label_size), formatFileSize(context, dest.sizeBytes))
        if (dest.lastModified > 0L) {
            addRow(fileSection, context.getString(R.string.file_detail_label_modified), formatModifiedDate(dest.lastModified))
        }
        addRow(fileSection, context.getString(R.string.file_detail_label_path), pathFor(source))

        val mediaSection = sectionContainer(content, R.string.file_detail_section_media)
        val mediaLoading = VoidText.body(context, context.getString(R.string.file_detail_metadata_loading), sizeSp = 16f, secondary = true)
        mediaSection.addView(mediaLoading)

        val tagsSectionTitle = sectionTitle(context.getString(R.string.file_detail_section_tags))
        val tagsSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        tagsSectionTitle.visibility = View.GONE
        tagsSection.visibility = View.GONE
        content.addView(tagsSectionTitle)
        content.addView(tagsSection)

        val tracksSectionTitle = sectionTitle(context.getString(R.string.file_detail_section_tracks))
        val tracksSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(tracksSectionTitle)
        content.addView(tracksSection)

        val btnPlay = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.file_detail_btn_play).trim()
            setIcon(R.drawable.ic_play_arrow)
            textSize = 22f
            setOnClickListener {
                activity.nativeSetAudioTrack(selectedAudioOrdinal)
                activity.nativeSetSubtitleTrack(selectedSubtitleOrdinal)
                onPlay(source)
            }
        }
        root.addView(btnPlay, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 20f) })

        host.showScreen(root)

        fun renderTracks(metadata: MediaMetadata) {
            tracksSection.removeAllViews()
            if (metadata.tracks.isEmpty()) {
                tracksSection.addView(
                    VoidText.body(context, context.getString(R.string.file_detail_tracks_empty), sizeSp = 16f, secondary = true)
                )
                return
            }
            metadata.videoTracks.forEach { track -> tracksSection.addView(buildVideoTrackRow(track)) }
            metadata.audioTracks.forEach { track ->
                tracksSection.addView(buildAudioTrackRow(track, selected = track.ordinal == selectedAudioOrdinal) {
                    selectedAudioOrdinal = track.ordinal
                    renderTracks(metadata)
                })
            }
            metadata.subtitleTracks.forEach { track ->
                tracksSection.addView(buildSubtitleTrackRow(track, selected = track.ordinal == selectedSubtitleOrdinal) {
                    selectedSubtitleOrdinal = if (selectedSubtitleOrdinal == track.ordinal) -1 else track.ordinal
                    renderTracks(metadata)
                })
            }
        }

        // Thumbnail e metadados chegam de forma assiciona -- a tela ja esta
        // visivel com o que se sabia de antemao (nome/tamanho/data/caminho).
        scope.launch {
            val bitmap = when (source) {
                is PlaybackSource.LocalFile ->
                    ThumbnailGenerator.getThumbnail(context, MediaEntry(dest.displayName, source.path, dest.sizeBytes, dest.lastModified, MediaType.VIDEO))
                is PlaybackSource.Smb, is PlaybackSource.Ftp, is PlaybackSource.Sftp ->
                    NetworkThumbnailGenerator.getThumbnail(context, activity, source)
                is PlaybackSource.Http, is PlaybackSource.Nfs, is PlaybackSource.Dlna -> null
            }
            if (bitmap != null) thumbnailView.setImageBitmap(bitmap)
        }

        scope.launch {
            val metadata = MediaMetadataReader.read(activity, source)
            mediaSection.removeView(mediaLoading)
            if (metadata == null) {
                mediaSection.addView(
                    VoidText.body(context, context.getString(R.string.file_detail_metadata_error), sizeSp = 16f, secondary = true)
                )
                tracksSection.addView(
                    VoidText.body(context, context.getString(R.string.file_detail_metadata_error), sizeSp = 16f, secondary = true)
                )
                return@launch
            }

            addRow(mediaSection, context.getString(R.string.file_detail_label_duration), formatDurationMs(metadata.durationMs))
            addRow(mediaSection, context.getString(R.string.file_detail_label_container), metadata.containerLong.ifEmpty { metadata.container })
            if (metadata.bitRate > 0) {
                addRow(mediaSection, context.getString(R.string.file_detail_label_bitrate), formatBitrate(metadata.bitRate))
            }
            metadata.videoTracks.firstOrNull()?.let { video ->
                if (video.width > 0 && video.height > 0) {
                    addRow(mediaSection, context.getString(R.string.file_detail_label_resolution), "${video.width}×${video.height}")
                }
                addRow(mediaSection, context.getString(R.string.file_detail_label_video_codec), video.codec)
            }
            val modeResId = MODE_LABEL_RES_IDS.getOrElse(metadata.format3dIndex) { R.string.player_mode_2d }
            val modeName = context.getString(modeResId)
            val format3dText = if (metadata.detectionConfidence >= 2 && metadata.format3dIndex != 0) {
                modeName + context.getString(R.string.file_detail_format3d_low_confidence_suffix)
            } else {
                modeName
            }
            addRow(mediaSection, context.getString(R.string.file_detail_label_format3d), format3dText)
            metadata.audioTracks.firstOrNull()?.let { audio ->
                addRow(mediaSection, context.getString(R.string.file_detail_label_audio_codec), audio.codec)
            }

            val tags = metadata.tags.filter { (key, value) -> TAG_LABEL_RES.containsKey(key.lowercase()) && value.isNotBlank() }
            if (tags.isNotEmpty()) {
                tagsSectionTitle.visibility = View.VISIBLE
                tagsSection.visibility = View.VISIBLE
                tags.forEach { (key, value) ->
                    val labelRes = TAG_LABEL_RES[key.lowercase()] ?: return@forEach
                    addRow(tagsSection, context.getString(labelRes), value)
                }
            }

            renderTracks(metadata)
        }
    }

    private fun sectionTitle(text: String) =
        VoidText.title(context, text, sizeSp = 18f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 8f))
        }

    private fun sectionContainer(parent: LinearLayout, titleRes: Int): LinearLayout {
        parent.addView(sectionTitle(context.getString(titleRes)))
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(section)
        return section
    }

    private fun addRow(parent: LinearLayout, label: String, value: String) {
        parent.addView(
            VoidText.body(context, context.getString(R.string.file_detail_row_format, label, value), sizeSp = 16f, secondary = true)
                .apply { setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f)) }
        )
    }

    private fun buildVideoTrackRow(track: TrackInfo): VoidListRow =
        VoidListRow(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
            val title = context.getString(R.string.file_detail_track_video_format, track.ordinal + 1, track.codec)
            val meta = if (track.width > 0 && track.height > 0) {
                "${track.width}×${track.height}" + if (track.fpsMilli > 0) " @ ${"%.2f".format(track.fpsMilli / 1000f)} fps" else ""
            } else null
            bind(title, meta = meta, showThumbnailSlot = false, iconResId = R.drawable.ic_movie)
        }

    private fun buildAudioTrackRow(track: TrackInfo, selected: Boolean, onSelect: () -> Unit): VoidListRow =
        VoidListRow(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
            val lang = track.language.ifBlank { context.getString(R.string.file_detail_track_lang_unknown) }
            val titleBase = context.getString(R.string.file_detail_track_audio_format, track.ordinal + 1, lang)
            val title = if (selected) "$titleBase ✓" else titleBase
            val metaParts = mutableListOf(track.codec)
            if (track.channels > 0) metaParts.add("${track.channels}ch")
            if (track.sampleRate > 0) metaParts.add("${track.sampleRate / 1000} kHz")
            bind(title, meta = metaParts.joinToString(" · "), showThumbnailSlot = false, iconResId = R.drawable.ic_movie)
            alpha = if (selected) 1.0f else 0.85f
            setOnClickListener { onSelect() }
        }

    private fun buildSubtitleTrackRow(track: TrackInfo, selected: Boolean, onSelect: () -> Unit): VoidListRow =
        VoidListRow(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
            val lang = track.language.ifBlank { context.getString(R.string.file_detail_track_lang_unknown) }
            val titleBase = context.getString(R.string.file_detail_track_subtitle_format, track.ordinal + 1, lang)
            val title = if (selected) "$titleBase ✓" else titleBase
            bind(
                title,
                meta = track.codec,
                showThumbnailSlot = false,
                iconResId = R.drawable.icon_subtitles
            )
            alpha = if (selected) 1.0f else 0.85f
            setOnClickListener { onSelect() }
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

    private fun subtitleFor(source: PlaybackSource): String = when (source) {
        is PlaybackSource.LocalFile -> context.getString(R.string.file_detail_subtitle_local)
        is PlaybackSource.Http -> context.getString(R.string.file_detail_subtitle_http)
        is PlaybackSource.Smb -> context.getString(R.string.file_detail_subtitle_smb_format, source.server.name)
        is PlaybackSource.Ftp -> context.getString(R.string.file_detail_subtitle_ftp_format, source.server.name)
        is PlaybackSource.Sftp -> context.getString(R.string.file_detail_subtitle_sftp_format, source.server.name)
        is PlaybackSource.Nfs -> "${source.server.name} (${source.server.host})"
        is PlaybackSource.Dlna -> "${source.server.name} (DLNA)"
    }

    private fun formatBitrate(bitsPerSecond: Long): String {
        val mbps = bitsPerSecond / 1_000_000.0
        return context.getString(R.string.file_detail_value_bitrate_format, mbps)
    }
}
