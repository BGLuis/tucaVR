package com.vrplayer.designsystem

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Linha de lista padrao Void: thumbnail 16:9 a esquerda + titulo + metadado
 * (secundario/mono) — usada pelo explorador de arquivos locais e pela
 * listagem de diretorios SMB.
 *
 * `bind()` decide o que mostrar; a view em si e so a casca reutilizavel.
 */
class VoidListRow(context: Context) : LinearLayout(context) {

    val thumbnail: ImageView
    private val textColumn: LinearLayout
    val titleView: android.widget.TextView
    val metaView: android.widget.TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = VoidTheme.dpToPx(context, 20f)
        val padV = VoidTheme.dpToPx(context, 16f)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            setColor(VoidTheme.colorSurface)
            cornerRadius = VoidTheme.dp(context, 10f)
        }

        val thumbHeight = VoidTheme.dpToPx(context, 44f)
        val thumbWidth = thumbHeight * 16 / 9
        thumbnail = ImageView(context).apply {
            layoutParams = LayoutParams(thumbWidth, thumbHeight).also {
                it.marginEnd = VoidTheme.dpToPx(context, 16f)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, 6f)
            }
            clipToOutline = true
        }
        addView(thumbnail)

        textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = VoidText.body(context, "", sizeSp = 20f)
        metaView = VoidText.mono(context, "", sizeSp = 14f, secondary = true).apply {
            visibility = View.GONE
        }
        textColumn.addView(titleView)
        textColumn.addView(metaView)
        addView(textColumn)
    }

    /**
     * @param showThumbnailSlot mantem o espaco reservado do thumbnail mesmo
     * quando a linha nao tem imagem (ex.: entrada "subir um nivel") — evita
     * o titulo "pular" horizontalmente conforme a linha tem ou nao thumbnail.
     */
    fun bind(title: String, meta: String? = null, showThumbnailSlot: Boolean = true) {
        titleView.text = title
        if (meta != null) {
            metaView.text = meta
            metaView.visibility = View.VISIBLE
        } else {
            metaView.visibility = View.GONE
        }
        thumbnail.setImageDrawable(null)
        thumbnail.visibility = if (showThumbnailSlot) View.INVISIBLE else View.GONE
    }
}
