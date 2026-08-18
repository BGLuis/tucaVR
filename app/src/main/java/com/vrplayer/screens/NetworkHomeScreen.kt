package com.vrplayer.screens

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vrplayer.R
import com.vrplayer.VRActivity
import com.vrplayer.designsystem.VoidListRow
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidTabRow
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.navigation.Destination
import com.vrplayer.navigation.PlaybackSource
import com.vrplayer.network.UrlHistoryStore
import com.vrplayer.history.isResumable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela de landing de rede (abas URL / SMB / FTP / SFTP).
 *
 * Fase 2 do redesign Void: substitui o antigo quad `NetworkPresentation`
 * (removido). Orquestra as quatro abas mas delega a construção de cada uma
 * para as screens especializadas (SMB, FTP, SFTP) e para a aba URL inline
 * abaixo. O índice da aba ativa é preservado entre navegações via
 * [activeTabIndex].
 *
 * As páginas de cada aba são construídas e enfileiradas em [FrameLayout];
 * visibilidade (VISIBLE/GONE) controla qual aba está ativa — padrão simples
 * e zero-dependency de bibliotecas de tabs.
 */
class NetworkHomeScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val urlHistory: UrlHistoryStore,
    private val smbPageBuilder: () -> View,
    private val ftpPageBuilder: () -> View,
    private val sftpPageBuilder: () -> View,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit,
    /** Índice da aba ativa (URL=0 / SMB=1 / FTP=2 / SFTP=3) — mutável pelo
     *  caller para preservar estado entre navegações. */
    var activeTabIndex: Int = 0
) {

    fun render() {
        val root = VoidPanelChrome.newRoot(context)
        // Voltar do painel principal de rede vai para o Home, simulando o appNav.backToHome() do original.
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.network_title), onBack = { onNavigate(Destination.Home) })
        )

        val pageContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val urlPage  = buildUrlPage()
        val smbPage  = smbPageBuilder()
        val ftpPage  = ftpPageBuilder()
        val sftpPage = sftpPageBuilder()
        val pages = listOf(urlPage, smbPage, ftpPage, sftpPage)

        pages.forEachIndexed { index, page ->
            page.visibility = if (index == activeTabIndex) View.VISIBLE else View.GONE
            pageContainer.addView(page)
        }

        val tabRow = VoidTabRow(
            context,
            listOf(
                context.getString(R.string.network_tab_url),
                context.getString(R.string.network_tab_smb),
                context.getString(R.string.network_tab_ftp),
                context.getString(R.string.network_tab_sftp)
            )
        ) { index ->
            activeTabIndex = index
            pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        }
        tabRow.setActiveIndex(activeTabIndex, notify = false)
        tabRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

        root.addView(tabRow)
        root.addView(pageContainer)

        host.showScreen(root)
    }

    // ---- Aba URL ----

    private fun buildUrlPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val urlInput = UiHelpers.buildVoidEditText(
            context,
            context.getString(R.string.network_url_hint),
            host
        ).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        page.addView(urlInput)

        val urlStatus = VoidText.body(context, "", sizeSp = 16f, secondary = true).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 16f))
        }

        val recentHeader = VoidText.title(
            context, context.getString(R.string.network_url_recent_header), sizeSp = 20f
        ).apply {
            setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
        }

        val recentContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun refreshRecentUrls() {
            recentContainer.removeAllViews()
            val entries = urlHistory.list()
            if (entries.isEmpty()) {
                recentContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_url_recent_empty), sizeSp = 16f, secondary = true)
                )
                return
            }
            entries.forEach { url ->
                val listRow = VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
                    bind(context.getString(R.string.network_url_recent_row_format, url), showThumbnailSlot = false)
                    titleView.typeface = VoidTheme.typefaceMono
                    titleView.textSize = 15f
                }
                listRow.setOnClickListener {
                    urlInput.setText(url)
                    playUrl(url, urlStatus) { refreshRecentUrls() }
                }
                recentContainer.addView(listRow)
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 8f))
        }

        val btnPaste = UiHelpers.buildPasteButton(context, activity, urlInput).apply {
            text = context.getString(R.string.network_url_btn_paste)
            textSize = 18f
        }
        val btnPlay = com.vrplayer.designsystem.VoidButton(context, com.vrplayer.designsystem.VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.network_url_btn_play)
            textSize = 18f
            setOnClickListener {
                playUrl(urlInput.text.toString().trim(), urlStatus) { refreshRecentUrls() }
            }
        }

        val btnMargin = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = VoidTheme.dpToPx(context, 12f) }
        row.addView(btnPaste, btnMargin)
        row.addView(btnPlay)

        page.addView(row)
        page.addView(urlStatus)
        page.addView(recentHeader)
        page.addView(recentContainer)

        refreshRecentUrls()
        return wrapInScroll(page)
    }

    /**
     * Valida a URL, adiciona ao histórico, inicia probe HTTP assíncrono para
     * informar se seek vai funcionar, e navega para o Player.
     *
     * T7.1: o probe HEAD-based NÃO bloqueia o play — o usuário pode tocar
     * mesmo sem range requests, só sem poder buscar.
     */
    private fun playUrl(url: String, statusView: android.widget.TextView, onHistoryChanged: () -> Unit) {
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            statusView.text = context.getString(R.string.network_url_status_invalid)
            return
        }
        statusView.text = context.getString(R.string.network_url_status_checking)
        urlHistory.add(url)
        onHistoryChanged()

        scope.launch {
            val probeResult = withContext(Dispatchers.IO) { activity.nativeProbeHttpUrl(url) }
            statusView.text = describeProbe(probeResult)
        }

        val source = PlaybackSource.Http(url)
        promptResumeOrPlay(source) { resumeAtMs ->
            activity.playUrl(url, resumeAtMs)
            onNavigate(Destination.Player(source))
        }
    }

    private fun describeProbe(result: String): String {
        if (result.startsWith("ERROR:")) {
            return context.getString(R.string.common_warning_format, result.removePrefix("ERROR:"))
        }
        val parts = result.split("\t")
        val seekable = parts.getOrNull(1) == "1"
        return if (seekable) {
            context.getString(R.string.network_url_status_seekable)
        } else {
            context.getString(R.string.network_url_status_not_seekable)
        }
    }

    private fun promptResumeOrPlay(source: PlaybackSource, onDecided: (resumeAtMs: Long?) -> Unit) {
        scope.launch {
            val existing = activity.historyTracker.findExisting(source)
            if (existing != null && existing.isResumable()) {
                // Reusar ResumePromptScreen seria ideal; aqui delegamos
                // direto para o caller via onDecided sem mostrar prompt,
                // pois o prompt é responsabilidade de VRPresentation.
                onDecided(existing.positionMs)
            } else {
                onDecided(null)
            }
        }
    }

    // Scroll wrapper necessário: páginas de rede têm conteúdo mais alto que
    // o canvas de 1024×768 da Presentation. Ver wrapNetworkPageInScroll no
    // original para o raciocínio completo.
    internal fun wrapInScroll(page: View): View =
        ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(page)
        }
}
