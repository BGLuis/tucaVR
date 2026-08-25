package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.tucavr.filebrowser.Format3DType

/**
 * Card de mídia para visualização em Grade (Grid View).
 * Contém miniatura 16:9 grande, badges de formato 3D e tipo, indicador destacado de pasta,
 * barra de progresso de retomada, título com destaque e metadados.
 */
class VoidGridCard(context: Context) : LinearLayout(context) {

    val thumbnail: ImageView
    val thumbnailContainer: FrameLayout
    val badge3D: TextView
    val badgeFolder: TextView
    val badgeType: ImageView
    val progressBar: ProgressBar
    val titleView: TextView
    val metaView: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = createNormalBorder()
        val pad = VoidTheme.dpToPx(context, 12f)
        setPadding(pad, pad, pad, pad)

        // Container 16:9 da miniatura
        thumbnailContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, VoidTheme.dpToPx(context, 160f)).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 10f)
            }
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, 8f)
            }
            clipToOutline = true
        }

        thumbnail = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        thumbnailContainer.addView(thumbnail)

        // Badge de Pasta Destacada (canto superior esquerdo)
        badgeFolder = TextView(context).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            setTextColor(Color.parseColor("#121214"))
            text = "📁 PASTA"
            val bPadH = VoidTheme.dpToPx(context, 8f)
            val bPadV = VoidTheme.dpToPx(context, 4f)
            setPadding(bPadH, bPadV, bPadH, bPadV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E5A93C")) // Âmbar destacado
                cornerRadius = VoidTheme.dp(context, 4f)
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                marginStart = VoidTheme.dpToPx(context, 8f)
                topMargin = VoidTheme.dpToPx(context, 8f)
            }
            visibility = View.GONE
        }
        thumbnailContainer.addView(badgeFolder)

        // Badge de formato 3D (canto superior direito)
        badge3D = TextView(context).apply {
            typeface = VoidTheme.typefaceMono
            textSize = 12f
            setTextColor(VoidTheme.colorText)
            val bPadH = VoidTheme.dpToPx(context, 8f)
            val bPadV = VoidTheme.dpToPx(context, 4f)
            setPadding(bPadH, bPadV, bPadH, bPadV)
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorBackground)
                cornerRadius = VoidTheme.dp(context, 4f)
                alpha = 210
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = VoidTheme.dpToPx(context, 8f)
                topMargin = VoidTheme.dpToPx(context, 8f)
            }
            visibility = View.GONE
        }
        thumbnailContainer.addView(badge3D)

        // Badge de Tipo (canto superior esquerdo - quando não for pasta)
        badgeType = ImageView(context).apply {
            val size = VoidTheme.dpToPx(context, 28f)
            val padI = VoidTheme.dpToPx(context, 4f)
            setPadding(padI, padI, padI, padI)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
                marginStart = VoidTheme.dpToPx(context, 8f)
                topMargin = VoidTheme.dpToPx(context, 8f)
            }
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorBackground)
                cornerRadius = VoidTheme.dp(context, 4f)
                alpha = 210
            }
            visibility = View.GONE
        }
        thumbnailContainer.addView(badgeType)

        // Barra de progresso de retomada (borda inferior da thumbnail)
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, VoidTheme.dpToPx(context, 4f)).apply {
                gravity = Gravity.BOTTOM
            }
            max = 1000
            progressDrawable = GradientDrawable().apply {
                setColor(VoidTheme.colorAccent)
            }
            visibility = View.GONE
        }
        thumbnailContainer.addView(progressBar)

        addView(thumbnailContainer)

        // Título
        titleView = VoidText.body(context, "", sizeSp = 16f).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(titleView)

        // Metadados
        metaView = VoidText.mono(context, "", sizeSp = 13f, secondary = true).apply {
            gravity = Gravity.START
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = VoidTheme.dpToPx(context, 4f)
            }
            visibility = View.GONE
        }
        addView(metaView)
    }

    private fun createNormalBorder() = GradientDrawable().apply {
        setColor(VoidTheme.colorSurface)
        cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
    }

    private fun createFolderBorder() = GradientDrawable().apply {
        setColor(VoidTheme.colorSurface)
        cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
        setStroke(VoidTheme.dpToPx(context, 2f), Color.parseColor("#E5A93C"))
    }

    /**
     * Vincula dados de exibição ao Card.
     */
    fun bind(
        title: CharSequence,
        meta: String? = null,
        format3D: Format3DType = Format3DType.FLAT_2D,
        iconResId: Int = 0,
        isFolder: Boolean = false,
        progressFraction: Float? = null
    ) {
        titleView.text = title

        if (meta != null) {
            metaView.text = meta
            metaView.visibility = View.VISIBLE
        } else {
            metaView.visibility = View.GONE
        }

        if (isFolder) {
            background = createFolderBorder()
            badgeFolder.visibility = View.VISIBLE
            badge3D.visibility = View.GONE
            badgeType.visibility = View.GONE
            progressBar.visibility = View.GONE
        } else {
            background = createNormalBorder()
            badgeFolder.visibility = View.GONE

            // Formato 3D badge
            if (format3D != Format3DType.FLAT_2D) {
                badge3D.text = when (format3D) {
                    Format3DType.SBS -> "3D SBS"
                    Format3DType.OU -> "3D OU"
                    Format3DType.VR_180 -> "180° VR"
                    Format3DType.VR_360 -> "360° VR"
                    Format3DType.FLAT_2D -> ""
                }
                badge3D.visibility = View.VISIBLE
            } else {
                badge3D.visibility = View.GONE
            }

            // Ícone de Tipo
            if (iconResId != 0) {
                badgeType.setImageResource(iconResId)
                badgeType.setColorFilter(PorterDuffColorFilter(VoidTheme.colorText, PorterDuff.Mode.SRC_IN))
                badgeType.visibility = View.VISIBLE
            } else {
                badgeType.visibility = View.GONE
            }

            // Progresso de reprodução
            if (progressFraction != null && progressFraction > 0.02f) {
                progressBar.progress = (progressFraction * 1000).toInt().coerceIn(0, 1000)
                progressBar.visibility = View.VISIBLE
            } else {
                progressBar.visibility = View.GONE
            }
        }

        thumbnail.setImageDrawable(null)
    }
}
