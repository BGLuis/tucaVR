package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.R
import com.tucavr.filebrowser.TrackKind

/**
 * Item descriptor for a subtitle track in the selection modal.
 */
data class SubtitleTrackItem(
    val index: Int,
    val language: String = "",
    val title: String = "",
    val isExternal: Boolean = false,
    val codec: String = "",
)

/**
 * Floating modal for selecting subtitle tracks and fine-tuning synchronization offset.
 *
 * Rendered on the floating Quad in VR space (VRModalPresentation) at 1.35m in front of the user.
 * Displays subtitle tracks with round language flags, type badges (embedded / external file),
 * language name and details line, ensuring a minimum touch target of 72 px.
 */
class SubtitleSelectionModal(
    context: Context,
    private val trackCount: Int,
    private var currentTrack: Int,
    private var currentOffsetMs: Long,
    private val onTrackSelected: (trackIndex: Int) -> Unit,
    private val onOffsetChanged: (newOffsetMs: Long) -> Unit,
    private val onDismiss: () -> Unit,
    private val trackItems: List<SubtitleTrackItem> = emptyList(),
) : FrameLayout(context) {
    private val trackButtons = mutableListOf<Pair<Int, LinearLayout>>()
    private val offsetValueText: TextView

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { onDismiss() }

        val panelWidth = VoidTheme.dpToPx(context, 680f)
        val panel =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurface)
                        cornerRadius = VoidTheme.dp(context, 16f)
                        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
                    }
                val pad = VoidTheme.dpToPx(context, 24f)
                setPadding(pad, pad, pad, pad)
                isClickable = true
                setOnClickListener { /* Consume clicks within the panel */ }
            }

        // Header: Title + Close Button
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 16f)
                    }
            }

        val title =
            TextView(context).apply {
                text = context.getString(R.string.subtitles_modal_title)
                typeface = VoidTheme.typefaceTitle
                textSize = 22f
                setTextColor(VoidTheme.colorText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        header.addView(title)

        val closeBtn =
            VoidIconButton(
                context,
                R.drawable.icon_x,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                val s = VoidTheme.dpToPx(context, 48f)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setOnClickListener { onDismiss() }
            }
        header.addView(closeBtn)
        panel.addView(header)

        // Subtitle track list container with scrolling
        val trackListContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        // Off option (track = -1)
        val offBtn = buildOffTrackRow()
        trackListContainer.addView(offBtn)
        trackButtons.add(-1 to offBtn)

        val effectiveTracks =
            if (trackItems.isNotEmpty()) {
                trackItems
            } else {
                (0 until trackCount).map { i ->
                    SubtitleTrackItem(
                        index = i,
                        language = "",
                        title = "",
                        isExternal = false,
                    )
                }
            }

        effectiveTracks.forEach { item ->
            val trackRow = buildSubtitleTrackRow(item)
            trackListContainer.addView(trackRow)
            trackButtons.add(item.index to trackRow)
        }

        val scroller =
            ScrollView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        VoidTheme.dpToPx(context, 260f),
                    )
                isFillViewport = true
                addView(trackListContainer)
            }
        panel.addView(scroller)

        // Synchronization Offset Adjustment Section
        val syncSection =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = VoidTheme.dpToPx(context, 20f)
                    }
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        cornerRadius = VoidTheme.dp(context, 12f)
                        setStroke(VoidTheme.dpToPx(context, 1f), VoidTheme.colorBorder)
                    }
                val p = VoidTheme.dpToPx(context, 16f)
                setPadding(p, p, p, p)
            }

        val syncLabel =
            TextView(context).apply {
                text = context.getString(R.string.subtitles_sync_label)
                typeface = VoidTheme.typefaceBody
                textSize = 16f
                setTextColor(VoidTheme.colorTextSecondary)
                gravity = Gravity.CENTER
            }
        syncSection.addView(syncLabel)

        val offsetSec = currentOffsetMs / 1000.0
        offsetValueText =
            TextView(context).apply {
                text = context.getString(R.string.subtitles_sync_value_format, offsetSec)
                typeface = VoidTheme.typefaceMono
                textSize = 22f
                setTextColor(VoidTheme.colorAccent)
                gravity = Gravity.CENTER
                setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 12f))
            }
        syncSection.addView(offsetValueText)

        val syncBtnsRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        fun addOffsetBtn(
            label: String,
            deltaMs: Long,
        ) {
            val btn =
                VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = label
                    textSize = 14f
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            val m = VoidTheme.dpToPx(context, 3f)
                            setMargins(m, 0, m, 0)
                        }
                    setOnClickListener {
                        val newOffset = if (deltaMs == 0L) 0L else currentOffsetMs + deltaMs
                        currentOffsetMs = newOffset
                        offsetValueText.text =
                            context.getString(
                                R.string.subtitles_sync_value_format,
                                currentOffsetMs / 1000.0,
                            )
                        onOffsetChanged(newOffset)
                    }
                }
            syncBtnsRow.addView(btn)
        }

        addOffsetBtn(context.getString(R.string.subtitles_sync_minus_500), -500L)
        addOffsetBtn(context.getString(R.string.subtitles_sync_minus_100), -100L)
        addOffsetBtn(context.getString(R.string.subtitles_sync_reset), 0L)
        addOffsetBtn(context.getString(R.string.subtitles_sync_plus_100), 100L)
        addOffsetBtn(context.getString(R.string.subtitles_sync_plus_500), 500L)

        syncSection.addView(syncBtnsRow)
        panel.addView(syncSection)

        addView(panel)
        refreshTrackHighlight()
    }

    private fun buildOffTrackRow(): LinearLayout {
        val rowHeight = maxOf(72, VoidTheme.dpToPx(context, 72f))

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 72
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight,
                ).apply {
                    bottomMargin = VoidTheme.dpToPx(context, 4f)
                }
            val padH = VoidTheme.dpToPx(context, 16f)
            setPadding(padH, 0, padH, 0)
            isClickable = true

            val icon =
                ImageView(context).apply {
                    setImageResource(R.drawable.sub_off)
                    val s = VoidTheme.dpToPx(context, 24f)
                    layoutParams =
                        LinearLayout.LayoutParams(s, s).apply {
                            rightMargin = VoidTheme.dpToPx(context, 14f)
                        }
                    setColorFilter(VoidTheme.colorTextSecondary)
                }
            addView(icon)

            val text =
                TextView(context).apply {
                    text = context.getString(R.string.subtitles_option_off)
                    typeface = VoidTheme.typefaceBody
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            addView(text)

            val checkIcon =
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_check)
                    val s = VoidTheme.dpToPx(context, 20f)
                    layoutParams = LinearLayout.LayoutParams(s, s)
                    setColorFilter(VoidTheme.colorAccent)
                }
            addView(checkIcon)

            setOnClickListener {
                currentTrack = -1
                refreshTrackHighlight()
                onTrackSelected(-1)
            }
        }
    }

    private fun buildSubtitleTrackRow(item: SubtitleTrackItem): LinearLayout {
        val displayInfo =
            LanguageRegionHelper.resolveTrackDisplayInfo(
                context = context,
                language = item.language,
                title = item.title,
                ordinal = item.index,
                kind = TrackKind.SUBTITLE,
                codec = item.codec,
                isExternal = item.isExternal,
            )

        val rowHeight = maxOf(72, VoidTheme.dpToPx(context, 72f))

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 72
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight,
                ).apply {
                    bottomMargin = VoidTheme.dpToPx(context, 4f)
                }
            val padH = VoidTheme.dpToPx(context, 16f)
            setPadding(padH, 0, padH, 0)
            isClickable = true

            // Circular flag icon
            val icon =
                ImageView(context).apply {
                    setImageResource(displayInfo.flagResId)
                    val s = VoidTheme.dpToPx(context, 32f)
                    layoutParams =
                        LinearLayout.LayoutParams(s, s).apply {
                            rightMargin = VoidTheme.dpToPx(context, 14f)
                        }
                }
            addView(icon)

            val textColumn =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

            // Top row: Language title + Badge (embedded / external file)
            val topRow =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                }

            val trackTitle =
                TextView(context).apply {
                    text = displayInfo.titleText
                    typeface = VoidTheme.typefaceBody
                    textSize = 16f
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                }
            topRow.addView(trackTitle)

            if (displayInfo.badgeText.isNotEmpty()) {
                val badge =
                    TextView(context).apply {
                        text = displayInfo.badgeText
                        typeface = VoidTheme.typefaceMono
                        textSize = 11f
                        setTextColor(VoidTheme.colorAccent)
                        background =
                            GradientDrawable().apply {
                                setColor(VoidTheme.colorSurfaceAlt)
                                cornerRadius = VoidTheme.dp(context, 4f)
                                setStroke(VoidTheme.dpToPx(context, 1f), VoidTheme.colorBorder)
                            }
                        val bPadH = VoidTheme.dpToPx(context, 6f)
                        val bPadV = VoidTheme.dpToPx(context, 2f)
                        setPadding(bPadH, bPadV, bPadH, bPadV)
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                leftMargin = VoidTheme.dpToPx(context, 8f)
                            }
                    }
                topRow.addView(badge)
            }
            textColumn.addView(topRow)

            // Details line: "PT-BR · embedded · SUBRIP" or "PT-PT · external file · SRT"
            if (displayInfo.detailsText.isNotEmpty()) {
                val trackDesc =
                    TextView(context).apply {
                        text = displayInfo.detailsText
                        typeface = VoidTheme.typefaceMono
                        textSize = 13f
                        setTextColor(VoidTheme.colorTextSecondary)
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = VoidTheme.dpToPx(context, 2f)
                            }
                    }
                textColumn.addView(trackDesc)
            }
            addView(textColumn)

            val checkIcon =
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_check)
                    val s = VoidTheme.dpToPx(context, 20f)
                    layoutParams = LinearLayout.LayoutParams(s, s)
                    setColorFilter(VoidTheme.colorAccent)
                }
            addView(checkIcon)

            setOnClickListener {
                currentTrack = item.index
                refreshTrackHighlight()
                onTrackSelected(item.index)
            }
        }
    }

    private fun refreshTrackHighlight() {
        for ((idx, row) in trackButtons) {
            val isSelected = (idx == currentTrack)
            val check = row.getChildAt(2) as? ImageView

            if (idx == -1) {
                val icon = row.getChildAt(0) as? ImageView
                val text = row.getChildAt(1) as? TextView
                if (isSelected) {
                    row.background =
                        GradientDrawable().apply {
                            setColor(VoidTheme.colorSurfaceAlt)
                            cornerRadius = VoidTheme.dp(context, 8f)
                            setStroke(VoidTheme.dpToPx(context, 1.5f), VoidTheme.colorAccent)
                        }
                    text?.setTextColor(VoidTheme.colorAccent)
                    icon?.setColorFilter(VoidTheme.colorAccent)
                    check?.visibility = View.VISIBLE
                } else {
                    row.background =
                        GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            cornerRadius = VoidTheme.dp(context, 8f)
                        }
                    text?.setTextColor(VoidTheme.colorText)
                    icon?.setColorFilter(VoidTheme.colorTextSecondary)
                    check?.visibility = View.GONE
                }
            } else {
                val textColumn = row.getChildAt(1) as? LinearLayout
                val topRow = textColumn?.getChildAt(0) as? LinearLayout
                val title = topRow?.getChildAt(0) as? TextView

                if (isSelected) {
                    row.background =
                        GradientDrawable().apply {
                            setColor(VoidTheme.colorSurfaceAlt)
                            cornerRadius = VoidTheme.dp(context, 8f)
                            setStroke(VoidTheme.dpToPx(context, 1.5f), VoidTheme.colorAccent)
                        }
                    title?.setTextColor(VoidTheme.colorAccent)
                    check?.visibility = View.VISIBLE
                } else {
                    row.background =
                        GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            cornerRadius = VoidTheme.dp(context, 8f)
                        }
                    title?.setTextColor(VoidTheme.colorText)
                    check?.visibility = View.GONE
                }
            }
        }
    }
}
