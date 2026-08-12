package com.vrplayer.designsystem

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vrplayer.R

/**
 * Casca comum de tela (root + cabecalho) reaproveitada pelas telas Home /
 * Arquivos locais / Rede / Player dentro de `VRPresentation`, e pelos outros
 * paineis (`NetworkPresentation`, `VRControlsPresentation`) no reskin visual.
 * Evita repetir `setBackgroundColor(VoidTheme.colorBackground); setPadding(...)`
 * em cada tela.
 */
object VoidPanelChrome {

    fun newRoot(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(VoidTheme.colorBackground)
        val padH = VoidTheme.dpToPx(context, 28f)
        val padV = VoidTheme.dpToPx(context, 24f)
        setPadding(padH, padV, padH, padV)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /**
     * Cabecalho de tela: titulo serifado (+ subtitulo mono opcional, ex. um
     * caminho de diretorio) e um botao "Voltar" (`VoidButton` estilo
     * SECONDARY) quando [onBack] for informado. Na tela Home, [onBack] deve
     * ser `null` — nao ha pra onde voltar dentro do painel.
     */
    fun buildHeader(
        context: Context,
        title: String,
        subtitle: String? = null,
        onBack: (() -> Unit)? = null
    ): LinearLayout {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 20f))
        }

        if (onBack != null) {
            val backButton = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(R.string.common_btn_back)
                textSize = 18f
                minHeight = 0
                val padH = VoidTheme.dpToPx(context, 18f)
                val padV = VoidTheme.dpToPx(context, 10f)
                setPadding(padH, padV, padH, padV)
                setOnClickListener { onBack() }
            }
            header.addView(backButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = VoidTheme.dpToPx(context, 16f) })
        }

        val titleColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(VoidText.title(context, title, sizeSp = 28f))
        if (subtitle != null) {
            titleColumn.addView(VoidText.mono(context, subtitle, sizeSp = 14f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 4f), 0, 0)
            })
        }
        header.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return header
    }
}
