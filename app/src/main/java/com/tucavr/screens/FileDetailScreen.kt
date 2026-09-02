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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import com.tucavr.designsystem.VoidFieldAction
import com.tucavr.designsystem.VoidTextField
import com.tucavr.history.AppDatabase
import com.tucavr.playlist.Playlist
import com.tucavr.playlist.PlaylistItem
import com.tucavr.playlist.sourceTypeString
import com.tucavr.playlist.toPlaylistItemUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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

        var detectedDurationMs = 0L

        val bottomActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = VoidTheme.dpToPx(context, 20f) }
        }

        val btnPlay = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.file_detail_btn_play).trim()
            setIcon(R.drawable.ic_play_arrow)
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                activity.nativeSetAudioTrack(selectedAudioOrdinal)
                activity.nativeSetSubtitleTrack(selectedSubtitleOrdinal)
                onPlay(source)
            }
        }

        val btnAddToPlaylist = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.playlists_add_to_playlist).trim()
            setIcon(R.drawable.ic_view_list)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = VoidTheme.dpToPx(context, 12f) }
            setOnClickListener {
                showAddToPlaylistDialog(dest, detectedDurationMs)
            }
        }

        bottomActions.addView(btnPlay)
        bottomActions.addView(btnAddToPlaylist)
        root.addView(bottomActions)

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
            detectedDurationMs = metadata.durationMs

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
            val modeResId = ScreenFormatCatalog.getLabelResId(metadata.format3dIndex)
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

    private fun showAddToPlaylistDialog(dest: Destination.FileDetail, durationMs: Long) {
        val playlistDao = AppDatabase.getInstance(context).playlistDao()
        val overlay = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
        }

        val dialogCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val w = VoidTheme.dpToPx(context, 540f)
            layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurface)
                cornerRadius = VoidTheme.dp(context, 16f)
                setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
            }
            val pad = VoidTheme.dpToPx(context, 24f)
            setPadding(pad, pad, pad, pad)
            isClickable = true
        }

        val titleView = VoidText.title(context, context.getString(R.string.playlists_add_to_playlist), sizeSp = 22f)
        dialogCard.addView(titleView)

        val playlistsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = VoidTheme.dpToPx(context, 16f) }
        }

        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 240f)
            )
            addView(playlistsContainer)
        }
        dialogCard.addView(scroller)

        val btnNewPlaylist = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = "+ " + context.getString(R.string.playlists_new).trim()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = VoidTheme.dpToPx(context, 12f) }
            setOnClickListener {
                host.hideOverlay(overlay)
                showCreateAndAddDialog(dest, durationMs)
            }
        }
        dialogCard.addView(btnNewPlaylist)

        val btnCancel = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.playlists_cancel_btn)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = VoidTheme.dpToPx(context, 12f) }
            setOnClickListener { host.hideOverlay(overlay) }
        }
        dialogCard.addView(btnCancel)

        overlay.addView(dialogCard)
        host.showOverlay(overlay)

        scope.launch {
            val playlists = withContext(Dispatchers.IO) {
                playlistDao.getAllPlaylists()
            }
            playlistsContainer.removeAllViews()
            if (playlists.isEmpty()) {
                val emptyMsg = VoidText.body(
                    context,
                    context.getString(R.string.playlists_empty),
                    sizeSp = 16f,
                    secondary = true
                ).apply {
                    setPadding(0, VoidTheme.dpToPx(context, 24f), 0, VoidTheme.dpToPx(context, 24f))
                    gravity = Gravity.CENTER
                }
                playlistsContainer.addView(emptyMsg)
            } else {
                playlists.forEach { pl ->
                    val row = VoidListRow(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).also { it.bottomMargin = VoidTheme.dpToPx(context, 6f) }
                        val metaText = if (pl.itemCount == 1) {
                            context.getString(R.string.playlists_item_count_singular, pl.itemCount)
                        } else {
                            context.getString(R.string.playlists_item_count_plural, pl.itemCount)
                        }
                        bind(
                            title = pl.name,
                            meta = metaText,
                            showThumbnailSlot = false,
                            iconResId = R.drawable.ic_view_list
                        )
                        setOnClickListener {
                            host.hideOverlay(overlay)
                            addItemToPlaylist(pl, dest, durationMs)
                        }
                    }
                    playlistsContainer.addView(row)
                }
            }
        }
    }

    private fun showCreateAndAddDialog(dest: Destination.FileDetail, durationMs: Long) {
        val overlay = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
        }

        val dialogCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val w = VoidTheme.dpToPx(context, 500f)
            layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurface)
                cornerRadius = VoidTheme.dp(context, 16f)
                setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
            }
            val pad = VoidTheme.dpToPx(context, 24f)
            setPadding(pad, pad, pad, pad)
            isClickable = true
        }

        val titleView = VoidText.title(context, context.getString(R.string.playlists_create_dialog_title), sizeSp = 22f)
        dialogCard.addView(titleView)

        val inputField = VoidTextField(
            context = context,
            host = host,
            hint = context.getString(R.string.playlists_name_hint),
            label = context.getString(R.string.playlists_name_hint),
            actions = setOf(VoidFieldAction.CLEAR, VoidFieldAction.PASTE)
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = VoidTheme.dpToPx(context, 16f)
                it.bottomMargin = VoidTheme.dpToPx(context, 24f)
            }
        }
        dialogCard.addView(inputField)

        val buttonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnCancel = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.playlists_cancel_btn)
            setOnClickListener { host.hideOverlay(overlay) }
        }

        val btnCreate = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.playlists_create_btn)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = VoidTheme.dpToPx(context, 12f) }
            setOnClickListener {
                val name = inputField.getText().trim()
                if (name.isNotEmpty()) {
                    host.hideOverlay(overlay)
                    scope.launch {
                        val newPlaylist = Playlist(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            createdAt = System.currentTimeMillis(),
                            itemCount = 0
                        )
                        withContext(Dispatchers.IO) {
                            AppDatabase.getInstance(context).playlistDao().insertPlaylist(newPlaylist)
                        }
                        addItemToPlaylist(newPlaylist, dest, durationMs)
                    }
                }
            }
        }

        buttonsRow.addView(btnCancel)
        buttonsRow.addView(btnCreate)
        dialogCard.addView(buttonsRow)

        overlay.addView(dialogCard)
        host.showOverlay(overlay)
        inputField.editText.requestFocus()
    }

    private fun addItemToPlaylist(playlist: Playlist, dest: Destination.FileDetail, durationMs: Long) {
        scope.launch {
            val item = PlaylistItem(
                id = UUID.randomUUID().toString(),
                playlistId = playlist.id,
                mediaUri = dest.source.toPlaylistItemUri(),
                title = dest.displayName,
                durationMs = durationMs,
                position = playlist.itemCount,
                sourceType = dest.source.sourceTypeString()
            )
            withContext(Dispatchers.IO) {
                AppDatabase.getInstance(context).playlistDao().addItemToPlaylist(item)
            }
        }
    }
}
