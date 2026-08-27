package com.tucavr.screens

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import com.tucavr.R
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidTheme
import com.tucavr.navigation.Destination

/**
 * Tela inicial do app — exibe os três pontos de entrada:
 * Arquivos Locais, Rede e Continuar Assistindo.
 *
 * Não tem estado próprio: é puramente declarativa. As ações de navegação
 * são delegadas via lambdas para manter esta classe livre de dependências
 * de navegação concretas.
 */
class HomeScreen(
    private val context: Context,
    private val host: ScreenHost,
    private val onNavigate: (Destination) -> Unit
) {

    fun render() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(VoidPanelChrome.buildHeader(context, title = context.getString(R.string.home_title)))

        val bigButtonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 20f) }

        val btnLocal = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.home_btn_local_files).trim()
            textSize = 24f
            setIcon(R.drawable.ic_folder)
            setOnClickListener { onNavigate(Destination.LocalFiles("")) }
        }
        val btnNetwork = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.home_btn_network).trim()
            textSize = 24f
            setIcon(R.drawable.ic_network)
            setOnClickListener { onNavigate(Destination.NetworkHome) }
        }
        // T9.4: histórico implementado — botão ativo. A tela lida com lista
        // vazia internamente; não precisa consultar Room aqui.
        val btnContinueWatching = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.home_btn_continue_watching).trim()
            textSize = 20f
            setIcon(R.drawable.ic_play_arrow)
            setOnClickListener { onNavigate(Destination.ContinueWatching) }
        }
        val btnSettings = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.home_btn_settings).trim()
            textSize = 18f
            setIcon(R.drawable.icon_settings)
            setOnClickListener { onNavigate(Destination.Settings) }
        }

        root.addView(btnLocal, bigButtonParams)
        root.addView(btnNetwork, bigButtonParams)
        root.addView(btnContinueWatching, bigButtonParams)
        root.addView(btnSettings, bigButtonParams)

        host.showScreen(root)
    }
}
