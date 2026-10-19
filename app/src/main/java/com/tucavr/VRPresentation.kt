package com.tucavr

import android.animation.Animator
import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.tucavr.designsystem.FullDebugStatsView
import com.tucavr.designsystem.KeyboardBinding
import com.tucavr.designsystem.VoidTheme
import com.tucavr.download.DownloadRepository
import com.tucavr.filebrowser.DirectoryNavigator
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.filebrowser.MediaType
import com.tucavr.filebrowser.mediaTypeForExtension
import com.tucavr.navigation.AppNavigator
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpCredentialStore
import com.tucavr.network.ServerCredentialStore
import com.tucavr.network.SftpCredentialStore
import com.tucavr.network.SmbCredentialStore
import com.tucavr.network.UrlHistoryStore
import com.tucavr.playlist.PlaylistDao
import com.tucavr.screens.ContextRailHost
import com.tucavr.screens.ContinueWatchingScreen
import com.tucavr.screens.DownloadsScreen
import com.tucavr.screens.HomeScreen
import com.tucavr.screens.LocalFilesScreen
import com.tucavr.screens.NetworkDiscoveryScreen
import com.tucavr.screens.NetworkDlnaScreen
import com.tucavr.screens.NetworkFtpScreen
import com.tucavr.screens.NetworkHomeScreen
import com.tucavr.screens.NetworkNfsScreen
import com.tucavr.screens.NetworkSftpScreen
import com.tucavr.screens.NetworkSmbScreen
import com.tucavr.screens.NetworkWebdavScreen
import com.tucavr.screens.PlaylistDetailScreen
import com.tucavr.screens.PlaylistsScreen
import com.tucavr.screens.ResumePromptScreen
import com.tucavr.screens.ScreenHost
import com.tucavr.screens.SettingsScreen
import com.tucavr.screens.components.ContextRailView
import com.tucavr.screens.components.SidebarView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * Fase 2 do redesign "Void": único quad/painel de UI além da tela de vídeo
 * e do painel de Controles (VRControlsPresentation). Hospeda o fluxo
 * completo Home → (Arquivos locais | Rede) → Player.
 *
 * **Canvas de 3 Zonas Proporcionais (1920x960):**
 * - Navegação (esquerda, 288 px / ~12°): SidebarView persistente
 * - Conteúdo (centro, 1200 px / ~50°): telas de lista, grade, detalhe
 * - Contexto (direita, 432 px / ~18°): ContextRailView ("Now Playing", fila e status)
 *
 * Expondo [ContextRailHost] para que telas possam alimentar a zona de contexto.
 */
class VRPresentation(
    outerContext: Context,
    display: Display,
    // Mesma armadilha documentada em VRControlsPresentation: `context`
    // (herdado de Presentation/Dialog) NÃO é a Activity real, é um
    // ContextThemeWrapper derivado do display-context. Guardamos a Activity
    // de verdade para poder chamar nativeX()/playFile()/playUrl() etc.
    private val activity: VRActivity,
) : Presentation(outerContext, display, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen), ContextRailHost {
    companion object {
        const val ZONE_NAV_WIDTH_PX = 288
        const val ZONE_CONTENT_WIDTH_PX = 1200
        const val ZONE_CONTEXT_WIDTH_PX = 432
    }

    // ---- Infraestrutura de navegação ----

    private val appNav = AppNavigator()

    // ---- Coroutine scope ----

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // ---- Stores de credenciais e histórico ----

    private lateinit var smbCredentials: SmbCredentialStore
    private lateinit var ftpCredentials: FtpCredentialStore
    private lateinit var sftpCredentials: SftpCredentialStore
    private lateinit var urlHistory: UrlHistoryStore

    // ---- Layout de 3 Zonas e Contêiner Raiz ----

    private lateinit var rootContainer: FrameLayout
    private lateinit var canvasLayout: LinearLayout
    private lateinit var navContainer: FrameLayout
    private lateinit var contentContainer: FrameLayout
    private lateinit var contextRailContainer: FrameLayout

    private lateinit var sidebarView: SidebarView
    private lateinit var contextRailView: ContextRailView

    // ---- Intro sequence (Epic #102 / R4 / #106) ----
    private var introController: IntroSequenceController? = null
    private var introView: LottieAnimationView? = null
    private val introHandler = Handler(Looper.getMainLooper())

    // Rastreia o KeyboardBinding com foco para fechar o teclado ao trocar de tela.
    private var keyboardTarget: KeyboardBinding? = null

    private val host: ScreenHost by lazy {
        object : ScreenHost {
            override fun showScreen(view: android.view.View) {
                // Fecha o teclado do campo que está prestes a ser destruído.
                keyboardTarget = null
                activity.hideNativeKeyboard()
                contentContainer.removeAllViews()
                contentContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
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
                    rootContainer.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            }

            override fun hideOverlay(view: android.view.View) {
                rootContainer.removeView(view)
            }

            override fun setContextContent(view: android.view.View) {
                this@VRPresentation.setContextContent(view)
            }

            override fun resetContextContent() {
                this@VRPresentation.resetContextContent()
            }

            override fun showFileDetail(
                entry: MediaEntry,
                source: PlaybackSource,
                onPlay: (resumeAtMs: Long?) -> Unit,
            ) {
                this@VRPresentation.showFileDetail(entry, source, onPlay)
            }

            override fun clearFileDetail() {
                this@VRPresentation.clearFileDetail()
            }
        }
    }

    override fun setContextContent(view: android.view.View) {
        if (::contextRailContainer.isInitialized) {
            contextRailContainer.removeAllViews()
            contextRailContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    override fun resetContextContent() {
        if (::contextRailContainer.isInitialized && ::contextRailView.isInitialized) {
            contextRailContainer.removeAllViews()
            contextRailView.clearFileDetail()
            contextRailView.updateState()
            contextRailContainer.addView(
                contextRailView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    override fun showFileDetail(
        entry: MediaEntry,
        source: PlaybackSource,
        onPlay: (resumeAtMs: Long?) -> Unit,
    ) {
        if (::contextRailContainer.isInitialized && ::contextRailView.isInitialized) {
            if (contextRailContainer.indexOfChild(contextRailView) < 0) {
                contextRailContainer.removeAllViews()
                contextRailContainer.addView(
                    contextRailView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            contextRailView.showFileDetail(entry, source, onPlay)
        }
    }

    override fun clearFileDetail() {
        if (::contextRailView.isInitialized) {
            contextRailView.clearFileDetail()
        }
    }

    // ---- Navegação de diretório local (drill-down não empilha Destination) ----

    private val dirNavigator = DirectoryNavigator(android.os.Environment.getExternalStorageDirectory())

    // ---- Screens ----

    private lateinit var homeScreen: HomeScreen
    private lateinit var localFilesScreen: LocalFilesScreen
    private lateinit var networkSmbScreen: NetworkSmbScreen
    private lateinit var networkNfsScreen: NetworkNfsScreen
    private lateinit var networkFtpScreen: NetworkFtpScreen
    private lateinit var networkSftpScreen: NetworkSftpScreen
    private lateinit var networkDlnaScreen: NetworkDlnaScreen
    private lateinit var networkDiscoveryScreen: NetworkDiscoveryScreen
    private lateinit var networkHomeScreen: NetworkHomeScreen
    private lateinit var networkWebdavScreen: NetworkWebdavScreen
    private lateinit var continueWatchingScreen: ContinueWatchingScreen
    private lateinit var resumePromptScreen: ResumePromptScreen
    private lateinit var settingsScreen: SettingsScreen
    private lateinit var playlistsScreen: PlaylistsScreen
    private lateinit var playlistDetailScreen: PlaylistDetailScreen
    private lateinit var photoViewerScreen: com.tucavr.screens.PhotoViewerScreen
    private lateinit var downloadsScreen: DownloadsScreen
    private lateinit var multicastLockManager: com.tucavr.network.MulticastLockManager
    private lateinit var savedServerDao: com.tucavr.network.SavedServerDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var serverCredentials: ServerCredentialStore

    // ---- Ciclo de vida ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = com.tucavr.history.AppDatabase.getInstance(activity)
        savedServerDao = db.savedServerDao()
        playlistDao = db.playlistDao()
        multicastLockManager = com.tucavr.network.MulticastLockManager(activity)
        smbCredentials = SmbCredentialStore(activity)
        ftpCredentials = FtpCredentialStore(activity)
        sftpCredentials = SftpCredentialStore(activity)
        serverCredentials = ServerCredentialStore(activity)
        urlHistory = UrlHistoryStore(activity)

        rootContainer =
            FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        setContentView(rootContainer)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window?.decorView?.background = null
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0.0f)
        window?.setFormat(android.graphics.PixelFormat.TRANSLUCENT)

        buildCanvasHierarchy()
        initScreens()

        if (IntroSequenceController.hasCompletedIntroOnce) {
            canvasLayout.visibility = android.view.View.VISIBLE
            render()
        } else {
            setupIntroSequence()
        }
    }

    private fun buildCanvasHierarchy() {
        canvasLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setBackgroundColor(VoidTheme.colorBackground)
            }

        // Zona 1: Navegação (esquerda, 288 px / ~12°)
        navContainer =
            FrameLayout(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ZONE_NAV_WIDTH_PX,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        sidebarView =
            SidebarView(
                context = context,
                appNav = appNav,
                onDestinationChanged = { render() },
            )
        navContainer.addView(
            sidebarView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        canvasLayout.addView(navContainer)

        // Zona 2: Conteúdo (centro, 1200 px / ~50°)
        contentContainer =
            FrameLayout(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ZONE_CONTENT_WIDTH_PX,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        canvasLayout.addView(contentContainer)

        // Zona 3: Contexto (direita, 432 px / ~18°)
        contextRailContainer =
            FrameLayout(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ZONE_CONTEXT_WIDTH_PX,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        contextRailView =
            ContextRailView(
                context = context,
                activity = activity,
                scope = scope,
                onBackToVideo = { activity.requestUiPanelHidden() },
            )
        contextRailContainer.addView(
            contextRailView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        canvasLayout.addView(contextRailContainer)

        rootContainer.addView(canvasLayout)
    }

    override fun dismiss() {
        disposeIntro()
        scope.cancel()
        super.dismiss()
    }

    // ---- Intro sequence lifecycle & input handlers ----

    private fun setupIntroSequence() {
        try {
            canvasLayout.visibility = android.view.View.INVISIBLE
            val lottie =
                LottieAnimationView(context).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    background = null
                    @Suppress("DEPRECATION")
                    setIgnoreDisabledSystemAnimations(true)
                    speed = 0.35f
                    repeatCount = 0
                    addAnimatorListener(
                        object : Animator.AnimatorListener {
                            override fun onAnimationStart(animation: Animator) {
                                Log.i("VRPresentation", "Intro Lottie animation started (speed=$speed)")
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                Log.i("VRPresentation", "Intro Lottie animation ended, holding static lock before Home")
                                introHandler.postDelayed({
                                    introController?.onAnimationComplete()
                                }, 2000L)
                            }

                            override fun onAnimationCancel(animation: Animator) {}

                            override fun onAnimationRepeat(animation: Animator) {}
                        },
                    )
                    setOnClickListener {
                        skipIntro()
                    }
                }
            introView = lottie
            rootContainer.addView(lottie)

            LottieCompositionFactory.fromAsset(context, "intro.json")
                .addListener { composition ->
                    try {
                        lottie.setComposition(composition)
                        lottie.speed = 0.35f
                        Log.i("VRPresentation", "Intro composition loaded: ${composition.duration}ms, state=${introController?.state}")
                        if (introController?.state == IntroSequenceController.State.PLAYING_ANIMATION) {
                            lottie.playAnimation()
                        }
                    } catch (e: Throwable) {
                        Log.w("VRPresentation", "Failed applying Lottie composition: ${e.message}, falling back to Home", e)
                        abortIntro()
                    }
                }
                .addFailureListener { error ->
                    Log.w("VRPresentation", "Failed to load intro.json: ${error.message}, falling back to Home")
                    abortIntro()
                }

            introController =
                IntroSequenceController(
                    isReducedMotion = { IntroSequenceController.isReducedMotionEnabled(context) },
                    onPlayAnimation = {
                        introView?.let { v ->
                            if (v.composition != null) {
                                v.playAnimation()
                            }
                        }
                    },
                    onShowStaticFinalFrame = { durationMs ->
                        introView?.let { it.progress = 1.0f }
                        introHandler.postDelayed(
                            { introController?.onReducedMotionTimerComplete() },
                            durationMs,
                        )
                    },
                    onTransitionToHome = {
                        disposeIntro()
                        canvasLayout.visibility = android.view.View.VISIBLE
                        render()
                    },
                )
            // The GLES backend is frozen and never sends onXrSessionFocused.
            if (BuildConfig.GRAPHICS_API != "VULKAN") introController?.start()
        } catch (e: Exception) {
            Log.w("VRPresentation", "Error during intro setup: ${e.message}, falling back to Home", e)
            disposeIntro()
            canvasLayout.visibility = android.view.View.VISIBLE
            render()
        }
    }

    fun onXrSessionFocused() {
        if (introController?.state == IntroSequenceController.State.IDLE) {
            introController?.start()
        }
    }

    // skip() is a no-op while the intro still waits for XR focus (IDLE).
    private fun abortIntro() {
        if (introController?.isIntroActive == true) {
            introController?.skip()
            return
        }
        introController = null
        disposeIntro()
        canvasLayout.visibility = android.view.View.VISIBLE
        render()
    }

    fun skipIntro(): Boolean {
        val controller = introController
        if (controller != null && controller.isIntroActive) {
            controller.skip()
            return true
        }
        return false
    }

    private fun disposeIntro() {
        introHandler.removeCallbacksAndMessages(null)
        introView?.let { view ->
            view.removeAllAnimatorListeners()
            view.removeAllUpdateListeners()
            view.cancelAnimation()
            view.clearAnimation()
            view.setImageDrawable(null)
            rootContainer.removeView(view)
        }
        introView = null
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (introController?.isIntroActive == true) {
            skipIntro()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (introController?.isIntroActive == true) {
            skipIntro()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Chamado por VRActivity.onResume() — recarrega a listagem local, se ativa. */
    fun loadFiles() {
        if (appNav.current is Destination.LocalFiles) {
            localFilesScreen.loadFiles()
        }
    }

    // ---- Inicialização das screens ----

    private fun initScreens() {
        resumePromptScreen =
            ResumePromptScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                onBack = { render() },
            )

        homeScreen =
            HomeScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                savedServerDao = savedServerDao,
                onNavigate = { dest -> navigateTo(dest) },
                onPlaySource = { source -> playSource(source) },
                onOpenBrowser = {
                    activity.openBrowserSession()
                },
            )

        localFilesScreen =
            LocalFilesScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                dirNavigator = dirNavigator,
                onNavigate = { dest -> navigateTo(dest) },
                onBack = { handleBack() },
                onPlayLocalVideo = { entry ->
                    if (entry.type == com.tucavr.filebrowser.MediaType.IMAGE) {
                        val dirFiles =
                            dirNavigator.currentPath.listFiles()
                                ?.filter {
                                    !it.isDirectory &&
                                        mediaTypeForExtension(it.extension) ==
                                        MediaType.IMAGE
                                }
                                ?.map {
                                    com.tucavr.filebrowser.MediaEntry(
                                        name = it.name,
                                        path = it.absolutePath,
                                        sizeBytes = it.length(),
                                        lastModified = it.lastModified(),
                                        type = com.tucavr.filebrowser.MediaType.IMAGE,
                                    )
                                }
                        val allImages = if (!dirFiles.isNullOrEmpty()) dirFiles else listOf(entry)
                        val idx = allImages.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
                        navigateTo(Destination.PhotoViewer(entry, allImages, idx))
                    } else {
                        playSource(PlaybackSource.LocalFile(entry.path, entry.sizeBytes))
                    }
                },
            )

        networkSmbScreen =
            NetworkSmbScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                credentialStore = smbCredentials,
                onNavigate = { dest -> navigateTo(dest) },
                onBack = { handleBack() },
                onPlay = { source -> playSource(source) },
            )

        networkNfsScreen =
            NetworkNfsScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                savedServerDao = savedServerDao,
                onNavigate = { dest -> navigateTo(dest) },
                onBack = { handleBack() },
                onPlay = { source -> playSource(source) },
            )

        networkFtpScreen =
            NetworkFtpScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                credentialStore = ftpCredentials,
                onNavigate = { dest -> navigateTo(dest) },
                onBack = { handleBack() },
                onPlay = { source -> playSource(source) },
            )

        networkSftpScreen =
            NetworkSftpScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                credentialStore = sftpCredentials,
                onNavigate = { dest -> navigateTo(dest) },
                onBack = { handleBack() },
                onPlay = { source -> playSource(source) },
            )

        networkDlnaScreen =
            NetworkDlnaScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                savedServerDao = savedServerDao,
                onPlayDlna = { server: com.tucavr.network.SavedServer, title: String, url: String, sizeBytes: Long ->
                    playSource(PlaybackSource.Dlna(server, title, url, sizeBytes))
                },
                onBack = { handleBack() },
            )

        networkDiscoveryScreen =
            NetworkDiscoveryScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                savedServerDao = savedServerDao,
                lockManager = multicastLockManager,
                onNavigate = { dest -> navigateTo(dest) },
                onConfigureServer = {
                        protocol: com.tucavr.network.ServerProtocol,
                        hostStr: String,
                        portNum: Int,
                        nameStr: String,
                        pathStr: String,
                    ->
                    networkHomeScreen.openAddForm(
                        protocol,
                        com.tucavr.screens.ServerPrefill(
                            host = hostStr,
                            port = portNum,
                            name = nameStr,
                            path = pathStr,
                        ),
                    )
                },
            )

        networkWebdavScreen =
            NetworkWebdavScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                savedServerDao = savedServerDao,
                credentialStore = serverCredentials,
                onNavigate = { dest -> navigateTo(dest) },
                onBack = { handleBack() },
                onPlay = { source -> playSource(source) },
            )

        networkHomeScreen =
            NetworkHomeScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                urlHistory = urlHistory,
                savedServerDao = savedServerDao,
                credentialStore = serverCredentials,
                lockManager = multicastLockManager,
                discoveryPageBuilder = { networkDiscoveryScreen.buildPage() },
                smbAddFormBuilder = { onSaved, prefill -> networkSmbScreen.buildAddServerForm(onSaved, prefill) },
                ftpAddFormBuilder = { onSaved, prefill -> networkFtpScreen.buildAddServerForm(onSaved, prefill) },
                sftpAddFormBuilder = { onSaved, prefill -> networkSftpScreen.buildAddServerForm(onSaved, prefill) },
                nfsAddFormBuilder = { onSaved, prefill -> networkNfsScreen.buildAddServerForm(onSaved, prefill) },
                webdavAddFormBuilder = { onSaved, prefill -> networkWebdavScreen.buildAddServerForm(onSaved, prefill) },
                onNavigate = { dest -> navigateTo(dest) },
                onBack = { handleBack() },
                onPlay = { source -> playSource(source) },
                onConfigureDiscoveredServer = { protocol, hostStr, portNum, nameStr, pathStr ->
                    networkHomeScreen.openAddForm(
                        protocol,
                        com.tucavr.screens.ServerPrefill(
                            host = hostStr,
                            port = portNum,
                            name = nameStr,
                            path = pathStr,
                        ),
                        fromWizard = false,
                    )
                },
            )

        continueWatchingScreen =
            ContinueWatchingScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                smbCredentials = smbCredentials,
                ftpCredentials = ftpCredentials,
                sftpCredentials = sftpCredentials,
                onNavigate = { dest -> navigateTo(dest) },
                onBack = { handleBack() },
            )

        settingsScreen =
            SettingsScreen(
                context = context,
                activity = activity,
                host = host,
                onBack = { handleBack() },
            )

        playlistsScreen =
            PlaylistsScreen(
                context = context,
                host = host,
                scope = scope,
                playlistDao = playlistDao,
                onNavigate = { dest -> navigateTo(dest) },
                onBack = { handleBack() },
            )

        playlistDetailScreen =
            PlaylistDetailScreen(
                context = context,
                host = host,
                scope = scope,
                playlistDao = playlistDao,
                onPlayPlaylist = { playlist, items, startIndex ->
                    activity.startPlaylist(playlist, items, startIndex)
                },
                onBack = { handleBack() },
            )

        photoViewerScreen =
            com.tucavr.screens.PhotoViewerScreen(
                context = context,
                activity = activity,
                host = host,
                scope = scope,
                onBack = { handleBack() },
            )

        downloadsScreen =
            DownloadsScreen(
                context = context,
                host = host,
                scope = scope,
                repository = DownloadRepository(context),
                onPlay = { source -> playSource(source) },
                onNavigate = { dest -> navigateTo(dest) },
                onBack = { handleBack() },
            )
    }

    // ---- Máquina de telas ----

    @Suppress("DEPRECATION")
    private fun render() {
        if (::sidebarView.isInitialized) {
            sidebarView.updateActiveDestination(appNav.current)
        }
        if (::contextRailView.isInitialized) {
            contextRailView.updateState()
        }
        when (val destination = appNav.current) {
            is Destination.Home -> homeScreen.render()
            is Destination.LocalFiles -> localFilesScreen.renderLocalFiles()
            is Destination.NetworkHome -> networkHomeScreen.render()
            is Destination.NetworkFiles -> networkSmbScreen.renderFiles(destination.server, destination.path)
            is Destination.NetworkNfsFiles -> networkNfsScreen.renderFiles(destination.server, destination.path)
            is Destination.NetworkDlnaFiles -> networkDlnaScreen.renderFiles(destination.server, destination.objectId)
            is Destination.NetworkFtpFiles -> networkFtpScreen.renderFiles(destination.server, destination.path)
            is Destination.NetworkSftpFiles -> networkSftpScreen.renderFiles(destination.server, destination.path)
            is Destination.NetworkWebdavFiles -> networkWebdavScreen.renderFiles(destination.server, destination.path)
            is Destination.ContinueWatching -> continueWatchingScreen.render()
            is Destination.Playlists -> playlistsScreen.render()
            is Destination.PlaylistDetail -> playlistDetailScreen.render(destination.playlistId)
            is Destination.Player -> {
                activity.requestUiPanelHidden()
            }
            is Destination.PhotoViewer ->
                photoViewerScreen.render(
                    destination.initialEntry,
                    destination.photoEntries,
                    destination.initialIndex,
                )
            is Destination.Downloads -> downloadsScreen.render()
            is Destination.Settings -> settingsScreen.render()
        }
    }

    @Deprecated("Now playing screen removed; use activity.requestUiPanelHidden() directly")
    fun onNavigateToPlayer(
        @Suppress("UNUSED_PARAMETER") source: PlaybackSource? = null,
    ) {
        activity.requestUiPanelHidden()
    }

    fun updatePlaybackProgress(
        currentSec: Float,
        totalSec: Float,
    ) {
        if (::contextRailView.isInitialized) {
            contextRailView.updateProgress(currentSec, totalSec)
        }
    }

    private var lastMediaIsLoading: Boolean? = null
    private var lastMediaIsPlaying: Boolean? = null

    fun updateMediaState(
        isLoading: Boolean,
        isPlaying: Boolean,
    ) {
        if (isLoading == lastMediaIsLoading && isPlaying == lastMediaIsPlaying) {
            return
        }
        lastMediaIsLoading = isLoading
        lastMediaIsPlaying = isPlaying
        if (::contextRailView.isInitialized) {
            contextRailView.updateState(force = false)
        }
    }

    fun onPlaybackStopped() {
        lastMediaIsLoading = false
        lastMediaIsPlaying = false
        if (::contextRailView.isInitialized) {
            contextRailView.updateState(force = true)
        }
    }

    fun popTo(predicate: (Destination) -> Boolean) {
        if (appNav.current is Destination.Settings) {
            settingsScreen.cleanup()
        }
        if (appNav.popTo(predicate)) {
            render()
        }
    }

    /** Pops any active Player destination from the navigation stack. */
    fun popPlayer() {
        popTo { it !is Destination.Player }
    }

    private fun navigateTo(destination: Destination) {
        if (appNav.current is Destination.Settings && destination !is Destination.Settings) {
            settingsScreen.cleanup()
        }
        appNav.navigateTo(destination)
        render()
    }

    /**
     * Ponto único de "tocar [source]": pergunta se retoma (via
     * [resumePromptScreen]), despacha pro `nativePlayXxx` certo por tipo de
     * fonte, e oculta o painel de UI para assistir ao vídeo. Usado tanto pelo double-click local quanto
     * pelo botão Reproduzir de [ContextRailView] (local e rede).
     */
    private fun playSource(
        source: PlaybackSource,
        resumeAtMs: Long? = null,
    ) {
        if (resumeAtMs != null) {
            when (source) {
                is PlaybackSource.LocalFile -> activity.playFile(source.path, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Http -> activity.playUrl(source.url, resumeAtMs)
                is PlaybackSource.Smb -> activity.playSmb(source.server, source.path, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Nfs -> activity.playNfs(source.server, source.path, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Dlna -> activity.playDlna(source.server, source.title, source.url, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Ftp -> activity.playFtp(source.server, source.path, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Sftp -> activity.playSftp(source.server, source.path, source.sizeBytes, resumeAtMs)
                is PlaybackSource.Webdav -> activity.playWebdav(source.server, source.path, source.sizeBytes, resumeAtMs)
            }
            activity.requestUiPanelHidden()
            return
        }
        resumePromptScreen.promptOrPlay(source) { promptResumeAtMs ->
            when (source) {
                is PlaybackSource.LocalFile -> activity.playFile(source.path, source.sizeBytes, promptResumeAtMs)
                is PlaybackSource.Http -> activity.playUrl(source.url, promptResumeAtMs)
                is PlaybackSource.Smb -> activity.playSmb(source.server, source.path, source.sizeBytes, promptResumeAtMs)
                is PlaybackSource.Nfs -> activity.playNfs(source.server, source.path, source.sizeBytes, promptResumeAtMs)
                is PlaybackSource.Dlna -> activity.playDlna(source.server, source.title, source.url, source.sizeBytes, promptResumeAtMs)
                is PlaybackSource.Ftp -> activity.playFtp(source.server, source.path, source.sizeBytes, promptResumeAtMs)
                is PlaybackSource.Sftp -> activity.playSftp(source.server, source.path, source.sizeBytes, promptResumeAtMs)
                is PlaybackSource.Webdav -> activity.playWebdav(source.server, source.path, source.sizeBytes, promptResumeAtMs)
            }
            activity.requestUiPanelHidden()
        }
    }

    private var fullDebugStatsView: FullDebugStatsView? = null
    private var lastStatsWire: String? = null

    fun isFullStatsActive(): Boolean = fullDebugStatsView != null

    fun showFullDebugStats() {
        if (fullDebugStatsView != null) return
        val view =
            FullDebugStatsView(
                context = context,
                onClose = {
                    hideFullDebugStats()
                    activity.onFullStatsDismissed()
                },
            )
        fullDebugStatsView = view
        rootContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        lastStatsWire?.let { wire ->
            view.updateStats(
                text = wire,
                meta = activity.currentMediaMetadata,
                source = activity.currentPlaybackSource,
                isCharging = activity.isBatteryCharging(),
                batteryPercent = activity.getBatteryPercent(),
                isDebuggable = activity.isDebuggable,
            )
        }
    }

    fun hideFullDebugStats() {
        val view = fullDebugStatsView ?: return
        rootContainer.removeView(view)
        fullDebugStatsView = null
    }

    fun updateFullDebugStats(text: String) {
        lastStatsWire = text
        val view = fullDebugStatsView ?: return
        view.updateStats(
            text = text,
            meta = activity.currentMediaMetadata,
            source = activity.currentPlaybackSource,
            isCharging = activity.isBatteryCharging(),
            batteryPercent = activity.getBatteryPercent(),
            isDebuggable = activity.isDebuggable,
        )
    }

    /**
     * Único ponto de "Voltar" do painel.
     *
     * Casos especiais (drill-down de diretório local e de rede) são tratados
     * pelas próprias screens — elas sobem um nível sem tocar no AppNavigator.
     * Quando já na raiz, o AppNavigator faz o pop e [render] re-renderiza.
     */
    fun handleBack(): Boolean {
        if (isFullStatsActive()) {
            hideFullDebugStats()
            activity.onFullStatsDismissed()
            return true
        }

        val current = appNav.current
        val handled =
            when {
                current is Destination.LocalFiles -> localFilesScreen.handleBack()
                current is Destination.NetworkFiles -> networkSmbScreen.handleBack(current.server)
                current is Destination.NetworkNfsFiles -> networkNfsScreen.handleBack(current.server)
                current is Destination.NetworkFtpFiles -> networkFtpScreen.handleBack(current.server)
                current is Destination.NetworkSftpFiles -> networkSftpScreen.handleBack(current.server)
                current is Destination.NetworkWebdavFiles -> networkWebdavScreen.handleBack(current.server)
                current is Destination.NetworkHome -> networkHomeScreen.handleBack()
                current is Destination.Settings -> {
                    settingsScreen.cleanup()
                    false
                }
                else -> false
            }
        if (!handled) {
            if (appNav.back()) {
                render()
                return true
            }
            return false
        }
        return true
    }

    /**
     * Rola suavemente a View rolável sob o cursor (x, y) ou a primeira encontrada na tela ativa.
     */
    fun dispatchScroll(
        x: Float,
        y: Float,
        scrollDeltaY: Float,
    ) {
        if (!::rootContainer.isInitialized) return
        val pixelX = (x * VRActivity.UI_DISPLAY_WIDTH).coerceIn(0f, VRActivity.UI_DISPLAY_WIDTH.toFloat())
        val pixelY = (y * VRActivity.UI_DISPLAY_HEIGHT).coerceIn(0f, VRActivity.UI_DISPLAY_HEIGHT.toFloat())
        val target =
            findScrollableViewAt(rootContainer, pixelX, pixelY)
                ?: (if (::contentContainer.isInitialized) findAnyScrollableView(contentContainer) else null)
                ?: findAnyScrollableView(rootContainer)
        target?.scrollBy(0, scrollDeltaY.toInt())
    }

    private fun findScrollableViewAt(
        parent: android.view.View,
        x: Float,
        y: Float,
    ): android.view.View? {
        if (!parent.isShown) return null
        val location = IntArray(2)
        parent.getLocationOnScreen(location)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val right = left + parent.width
        val bottom = top + parent.height

        if (x !in left..right || y !in top..bottom) {
            return null
        }

        if (parent is ViewGroup) {
            for (i in parent.childCount - 1 downTo 0) {
                val child = parent.getChildAt(i)
                val scrollable = findScrollableViewAt(child, x, y)
                if (scrollable != null) return scrollable
            }
        }

        if (parent is ScrollView ||
            parent is RecyclerView ||
            parent.canScrollVertically(1) ||
            parent.canScrollVertically(-1)
        ) {
            return parent
        }
        return null
    }

    private fun findAnyScrollableView(parent: android.view.View): android.view.View? {
        if (!parent.isShown) return null
        if (parent is android.widget.ScrollView || parent is androidx.recyclerview.widget.RecyclerView) {
            return parent
        }
        if (parent is ViewGroup) {
            for (i in 0 until parent.childCount) {
                val found = findAnyScrollableView(parent.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
