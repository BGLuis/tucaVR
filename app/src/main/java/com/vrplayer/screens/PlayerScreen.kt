package com.vrplayer.screens

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vrplayer.R
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.navigation.PlaybackSource

/**
 * Tela de estado pós-seleção de mídia: exibe o que está sendo reproduzido
 * e uma dica para usar o painel de Controles.
 *
 * Esta tela é intencionalmente mínima — todo o controle de playback real
 * fica no VRControlsPresentation (outro quad/painel). Aqui apenas confirma
 * ao usuário qual fonte está tocando e instrui sobre o painel de controles.
 */
class PlayerScreen(
    private val context: Context,
    private val host: ScreenHost,
    private val onBack: () -> Unit
) {

    fun render(source: PlaybackSource) {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.player_title),
                onBack = { onBack() }
            )
        )

        val label = resolveSourceLabel(source)
        root.addView(VoidText.mono(context, label, sizeSp = 16f))
        root.addView(VoidText.body(
            context,
            context.getString(R.string.player_label_controls_hint),
            sizeSp = 16f,
            secondary = true
        ).apply {
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, 0)
        })

        host.showScreen(root)
    }

    /**
     * Gera o texto de rótulo da fonte de reprodução ativa.
     *
     * Interpolação com 2 argumentos posicionais para SMB/FTP/SFTP (T8.5):
     * permite reordenar "servidor/caminho" em idiomas onde isso fizesse
     * sentido, embora PT-BR e EN não precisem reordenar.
     */
    private fun resolveSourceLabel(source: PlaybackSource): String = when (source) {
        is PlaybackSource.LocalFile -> source.path
        is PlaybackSource.Http     -> source.url
        is PlaybackSource.Smb      -> context.getString(
            R.string.player_label_smb_source_format, source.server.name, source.path
        )
        is PlaybackSource.Ftp      -> context.getString(
            R.string.player_label_ftp_source_format, source.server.name, source.path
        )
        is PlaybackSource.Sftp     -> context.getString(
            R.string.player_label_sftp_source_format, source.server.name, source.path
        )
    }
}
