package com.tucavr

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import com.tucavr.designsystem.KeyboardBinding
import com.tucavr.filebrowser.DirectoryNavigator
import com.tucavr.navigation.AppNavigator
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpCredentialStore
import com.tucavr.network.SftpCredentialStore
import com.tucavr.network.SmbCredentialStore
import com.tucavr.network.UrlHistoryStore
import com.tucavr.screens.ContinueWatchingScreen
import com.tucavr.screens.FileDetailScreen
import com.tucavr.screens.HomeScreen
import com.tucavr.screens.LocalFilesScreen
import com.tucavr.screens.NetworkDiscoveryScreen
import com.tucavr.screens.NetworkDlnaScreen
import com.tucavr.screens.NetworkFtpScreen
import com.tucavr.screens.NetworkHomeScreen
import com.tucavr.screens.NetworkNfsScreen
import com.tucavr.screens.NetworkSftpScreen
import com.tucavr.screens.NetworkSmbScreen
import com.tucavr.screens.PlayerScreen
import com.tucavr.screens.ResumePromptScreen
import com.tucavr.screens.ScreenHost
import com.tucavr.screens.SettingsScreen
import com.tucavr.designsystem.VoidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fase 2 do redesign "Void": único quad/painel de UI além da tela de vídeo
 * e do painel de Controles (VRControlsPresentation). Hospeda o fluxo
 * completo Home → (Arquivos locais | Rede) → Player.
 *
 * **Papel deste arquivo após a refatoração:**
 * Orquestrador puro — instancia as dependências compartilhadas (stores,
 * coroutine scope, navigator) e conecta as screens via injeção de lambdas.
 * Toda a lógica de renderização e estado específico de cada tela foi movida
 * para `com.tucavr.screens.*`. Ver cada arquivo para detalhes.
 *
 * **Navegação:** conduzida por [AppNavigator] (Kotlin puro, testável sem
 * Android). O botão físico B/Y continua alternando a visibilidade do quad;
 * o botão "Voltar" Void dentro do painel anda pelo back-stack do navigator.
 *
 * **Teclado nativo:** VirtualDisplay não recebe IME do sistema (restrição
 * AOSP de segurança). A solução usa um proxy de teclado real na Activity —
 * ver VRActivity.showNativeKeyboardFor() e a seção "TECLADO NATIVO" no
 * histórico de commits para o raciocínio completo.
 */
class VRPresentation(
    outerContext: Context,
    display: Display,
    // Mesma armadilha documentada em VRControlsPresentation: `context`
    // (herdado de Presentation/Dialog) NÃO é a Activity real, é um
    // ContextThemeWrapper derivado do display-context. Guardamos a Activity
    // de verdade para poder chamar nativeX()/playFile()/playUrl() etc.
    private val activity: VRActivity
) : Presentation(outerContext, display, android.R.style.Theme_NoTitleBar_Fullscreen) {

    // ---- Infraestrutura de navegação ----

    private val appNav = AppNavigator()

    // ---- Coroutine scope ----

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // ---- Stores de credenciais e histórico ----

    private lateinit var smbCredentials: SmbCredentialStore
    private lateinit var ftpCredentials: FtpCredentialStore
    private lateinit var sftpCredentials: SftpCredentialStore
    private lateinit var urlHistory: UrlHistoryStore

    // ---- Host de tela (contrato mínimo que as screens conhecem) ----

    private lateinit var screenHost: FrameLayout

    // Rastreia o KeyboardBinding com foco para fechar o teclado ao trocar de tela.
    private var keyboardTarget: KeyboardBinding? = null

    private val host: ScreenHost by lazy {
        object : ScreenHost {
            override fun showScreen(view: android.view.View) {
                // Fecha o teclado do campo que está prestes a ser destruído.
                keyboardTarget = null
                activity.hideNativeKeyboard()
                screenHost.removeAllViews()
                screenHost.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }
            override fun showNativeKeyboard(binding: KeyboardBinding) {
                keyboardTarget = binding
                activity.showNativeKeyboardFor(binding)
            }
            override fun hideNativeKeyboard() {
                keyboardTarget = null
                activity.hideNativeKeyboard()
            }
            override fun syncKeyboard(binding: KeyboardBinding) {
                if (keyboardTarget === binding) {
                    activity.syncKeyboardText(binding)
                }
            }
            override fun showOverlay(view: android.view.View) {
                if (view.parent == null) {
                    screenHost.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                }
            }
            override fun hideOverlay(view: android.view.View) {
                screenHost.removeView(view)
            }
        }
    }

    // ---- Navegação de diretório local (drill-down não empilha Destination) ----

    private val dirNavigator = DirectoryNavigator(android.os.Environment.getExternalStorageDirectory())

    // ---- Screens ----

    private lateinit var homeScreen: HomeScreen
    private lateinit var localFilesScreen: LocalFilesScreen
    private lateinit var fileDetailScreen: FileDetailScreen
    private lateinit var networkSmbScreen: NetworkSmbScreen
    private lateinit var networkNfsScreen: NetworkNfsScreen
    private lateinit var networkFtpScreen: NetworkFtpScreen
    private lateinit var networkSftpScreen: NetworkSftpScreen
    private lateinit var networkDlnaScreen: NetworkDlnaScreen
    private lateinit var networkDiscoveryScreen: NetworkDiscoveryScreen
    private lateinit var networkHomeScreen: NetworkHomeScreen
    private lateinit var continueWatchingScreen: ContinueWatchingScreen
    private lateinit var playerScreen: PlayerScreen
    private lateinit var resumePromptScreen: ResumePromptScreen
    private lateinit var settingsScreen: SettingsScreen
    private lateinit var multicastLockManager: com.tucavr.network.MulticastLockManager
    private lateinit var savedServerDao: com.tucavr.network.SavedServerDao

    // ---- Ciclo de vida ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db          = com.tucavr.history.AppDatabase.getInstance(activity)
        savedServerDao  = db.savedServerDao()
        multicastLockManager = com.tucavr.network.MulticastLockManager(activity)
        smbCredentials  = SmbCredentialStore(activity)
        ftpCredentials  = FtpCredentialStore(activity)
        sftpCredentials = SftpCredentialStore(activity)
        urlHistory      = UrlHistoryStore(activity)

        screenHost = FrameLayout(context).apply {
            setBackgroundColor(VoidTheme.colorBackground)
        }
        setContentView(screenHost)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        initScreens()
        render()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    /** Chamado por VRActivity.onResume() — recarrega a listagem local, se ativa. */
    fun loadFiles() {
        if (appNav.current is Destination.LocalFiles) {
            localFilesScreen.loadFiles()
        }
    }

    // ---- Inicialização das screens ----

    private fun initScreens() {
        resumePromptScreen = ResumePromptScreen(
            context   = context,
            activity  = activity,
            host      = host,
            scope     = scope,
            onBack    = { render() }
        )

        homeScreen = HomeScreen(
            context    = context,
            host       = host,
            onNavigate = { dest -> navigateTo(dest) }
        )

        localFilesScreen = LocalFilesScreen(
            context       = context,
            activity      = activity,
            host          = host,
            scope         = scope,
            dirNavigator  = dirNavigator,
            onNavigate    = { dest -> navigateTo(dest) },
            onBack        = { handleBack() },
            onPlayLocalVideo = { entry -> playSource(PlaybackSource.LocalFile(entry.path, entry.sizeBytes)) }
        )

        fileDetailScreen = FileDetailScreen(
            context  = context,
            activity = activity,
            host     = host,
            scope    = scope,
            onBack   = { handleBack() },
            onPlay   = { source -> playSource(source) }
        )

        networkSmbScreen = NetworkSmbScreen(
            context               = context,
            activity              = activity,
            host                  = host,
            scope                 = scope,
            credentialStore       = smbCredentials,
            onNavigate            = { dest -> navigateTo(dest) },
            onBack                = { handleBack() }
        )

        networkNfsScreen = NetworkNfsScreen(
            context               = context,
            activity              = activity,
            host                  = host,
            scope                 = scope,
            savedServerDao        = savedServerDao,
            onNavigate            = { dest -> navigateTo(dest) },
            onBack                = { handleBack() }
        )

        networkFtpScreen = NetworkFtpScreen(
            context               = context,
            activity              = activity,
            host                  = host,
            scope                 = scope,
            credentialStore       = ftpCredentials,
            onNavigate            = { dest -> navigateTo(dest) },
            onBack                = { handleBack() }
        )

        networkSftpScreen = NetworkSftpScreen(
            context               = context,
            activity              = activity,
            host                  = host,
            scope                 = scope,
            credentialStore       = sftpCredentials,
            onNavigate            = { dest -> navigateTo(dest) },
            onBack                = { handleBack() }
        )

        networkDlnaScreen = NetworkDlnaScreen(
            context        = context,
            activity       = activity,
            host           = host,
            scope          = scope,
            savedServerDao = savedServerDao,
            onNavigate     = { dest -> navigateTo(dest) },
            onPlayDlna     = { server: com.tucavr.network.SavedServer, title: String, url: String, sizeBytes: Long ->
                playSource(PlaybackSource.Dlna(server, title, url, sizeBytes))
            },
            onBack         = { handleBack() }
        )

        networkDiscoveryScreen = NetworkDiscoveryScreen(
            context               = context,
            activity              = activity,
            host                  = host,
            scope                 = scope,
            savedServerDao        = savedServerDao,
            lockManager           = multicastLockManager,
            onNavigate            = { dest -> navigateTo(dest) },
            onConfigureServer     = { protocol: com.tucavr.network.ServerProtocol, hostStr: String, portNum: Int, nameStr: String, pathStr: String ->
                when (protocol) {
                    com.tucavr.network.ServerProtocol.DLNA -> networkHomeScreen.activeTabIndex = 1
                    com.tucavr.network.ServerProtocol.SMB  -> networkHomeScreen.activeTabIndex = 3
                    com.tucavr.network.ServerProtocol.NFS  -> networkHomeScreen.activeTabIndex = 4
                    com.tucavr.network.ServerProtocol.FTP  -> networkHomeScreen.activeTabIndex = 5
                    com.tucavr.network.ServerProtocol.SFTP -> networkHomeScreen.activeTabIndex = 6
                    else -> networkHomeScreen.activeTabIndex = 2
                }
                render()
            }
        )

        networkHomeScreen = NetworkHomeScreen(
            context               = context,
            activity              = activity,
            host                  = host,
            scope                 = scope,
            urlHistory            = urlHistory,
            discoveryPageBuilder  = { networkDiscoveryScreen.buildPage() },
            dlnaPageBuilder       = { networkDlnaScreen.buildPage() },
            smbPageBuilder        = { networkSmbScreen.buildPage() },
            nfsPageBuilder        = { networkNfsScreen.buildPage() },
            ftpPageBuilder        = { networkFtpScreen.buildPage() },
            sftpPageBuilder       = { networkSftpScreen.buildPage() },
            onNavigate            = { dest -> navigateTo(dest) },
            onBack                = { handleBack() }
        )

        continueWatchingScreen = ContinueWatchingScreen(
            context         = context,
            activity        = activity,
            host            = host,
            scope           = scope,
            smbCredentials  = smbCredentials,
            ftpCredentials  = ftpCredentials,
            sftpCredentials = sftpCredentials,
            onNavigate      = { dest -> navigateTo(dest) },
            onBack          = { handleBack() }
        )

        playerScreen = PlayerScreen(
            context  = context,
            activity = activity,
            host     = host,
            scope    = scope,
            onBack   = { handleBack() }
        )

        settingsScreen = SettingsScreen(
            context  = context,
            activity = activity,
            host     = host,
            onBack   = { handleBack() }
        )
    }

    // ---- Máquina de telas ----

    private fun render() {
        when (val destination = appNav.current) {
            is Destination.Home             -> homeScreen.render()
            is Destination.LocalFiles       -> localFilesScreen.renderLocalFiles()
            is Destination.FileDetail       -> fileDetailScreen.render(destination)
            is Destination.NetworkHome      -> networkHomeScreen.render()
            is Destination.NetworkFiles     -> networkSmbScreen.renderFiles(destination.server)
            is Destination.NetworkNfsFiles  -> networkNfsScreen.renderFiles(destination.server, destination.path)
            is Destination.NetworkDlnaFiles -> networkDlnaScreen.renderFiles(destination.server, destination.objectId)
            is Destination.NetworkFtpFiles  -> networkFtpScreen.renderFiles(destination.server)
            is Destination.NetworkSftpFiles -> networkSftpScreen.renderFiles(destination.server)
            is Destination.ContinueWatching -> continueWatchingScreen.render()
            is Destination.Player           -> playerScreen.render(destination.source)
            is Destination.Settings         -> settingsScreen.render()
        }
    }

    private fun navigateTo(destination: Destination) {
        appNav.navigateTo(destination)
        render()
    }

    /**
     * Ponto único de "tocar [source]": pergunta se retoma (via
     * [resumePromptScreen]), despacha pro `nativePlayXxx` certo por tipo de
     * fonte, e navega pro Player. Usado tanto pelo double-click local quanto
     * pelo botão Reproduzir de [FileDetailScreen] (local e rede).
     */
    private fun playSource(source: PlaybackSource) {
        resumePromptScreen.promptOrPlay(source) { resumeAtMs ->
            when (source) {
                is PlaybackSource.LocalFile -> activity.playFile(source.path, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Http      -> activity.playUrl(source.url, resumeAtMs)
                is PlaybackSource.Smb       -> activity.playSmb(source.server, source.path, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Nfs       -> activity.playNfs(source.server, source.path, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Dlna      -> activity.playDlna(source.server, source.title, source.url, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Ftp       -> activity.playFtp(source.server, source.path, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Sftp      -> activity.playSftp(source.server, source.path, source.sizeBytes, resumeAtMs)
            }
            navigateTo(Destination.Player(source))
        }
    }

    /**
     * Único ponto de "Voltar" do painel.
     *
     * Casos especiais (drill-down de diretório local e de rede) são tratados
     * pelas próprias screens — elas sobem um nível sem tocar no AppNavigator.
     * Quando já na raiz, o AppNavigator faz o pop e [render] re-renderiza.
     */
    private fun handleBack() {
        val current = appNav.current
        val handled = when {
            current is Destination.LocalFiles       -> localFilesScreen.handleBack()
            current is Destination.NetworkFiles     -> networkSmbScreen.handleBack(current.server)
            current is Destination.NetworkNfsFiles  -> {
                networkNfsScreen.handleBack()
                true
            }
            current is Destination.NetworkFtpFiles  -> networkFtpScreen.handleBack(current.server)
            current is Destination.NetworkSftpFiles -> networkSftpScreen.handleBack(current.server)
            current is Destination.NetworkHome      -> {
                appNav.navigateTo(Destination.Home)
                render()
                true
            }
            else -> false
        }
        if (!handled) {
            if (appNav.back()) {
                render()
            }
        }
    }
}
