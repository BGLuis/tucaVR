package com.vrplayer.screens

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vrplayer.R
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.navigation.Destination

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
            text = context.getString(R.string.home_btn_local_files)
            textSize = 24f
            setOnClickListener { onNavigate(Destination.LocalFiles("")) }
        }
        val btnNetwork = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.home_btn_network)
            textSize = 24f
            setOnClickListener { onNavigate(Destination.NetworkHome) }
        }
        // T9.4: histórico implementado — botão ativo. A tela lida com lista
        // vazia internamente; não precisa consultar Room aqui.
        val btnContinueWatching = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.home_btn_continue_watching)
            textSize = 20f
            setOnClickListener { onNavigate(Destination.ContinueWatching) }
        }

        root.addView(btnLocal, bigButtonParams)
        root.addView(btnNetwork, bigButtonParams)
        root.addView(btnContinueWatching, bigButtonParams)

        host.showScreen(root)
    }
}
