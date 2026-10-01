package com.tucavr.browser.engine

import android.content.Context
import com.tucavr.debug.VRLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Main manager for the GeckoView engine and web session.
 *
 * Responsibilities:
 * 1. Initialize the shared [GeckoRuntime] with Widevine DRM enabled (`--enable-media-drm`).
 * 2. Configure [GeckoSession] with a tablet/desktop user agent and desktop viewport.
 * 3. Expose navigation, lifecycle, and reactive state ([StateFlow]).
 *
 * FUTURE EXTENSION NOTE:
 * This class is the primary integration point for controlling web playback from the video
 * player's controls. Methods such as [loadUrl], [goBack], [goForward], [reload], and the
 * [session] object should be preserved and extended to send playback scripts/commands
 * (Play, Pause, Seek, Volume) to the active page through GeckoSession WebExtension/JS APIs.
 */
class VRBrowserController(
    context: Context,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val densityDpi: Int = DEFAULT_DPI
) {

    companion object {
        const val DEFAULT_WIDTH = 2560
        const val DEFAULT_HEIGHT = 1440
        const val DEFAULT_DPI = 320

        @Volatile
        private var sharedRuntime: GeckoRuntime? = null

        fun getOrCreateRuntime(context: Context): GeckoRuntime {
            return sharedRuntime ?: synchronized(this) {
                sharedRuntime ?: createRuntime(context).also { sharedRuntime = it }
            }
        }

        private fun createRuntime(context: Context): GeckoRuntime {
            val settings = GeckoRuntimeSettings.Builder()
                .arguments(arrayOf("--enable-media-drm"))
                .displayDensityOverride(2.2f)
                .fontSizeFactor(1.5f)
                .automaticFontSizeAdjustment(true)
                .forceUserScalableEnabled(true)
                .build()
            return GeckoRuntime.create(context.applicationContext, settings)
        }
    }

    private val runtime: GeckoRuntime = getOrCreateRuntime(context)

    /**
     * Active GeckoView session.
     * Kept for future web playback commands (e.g., HTML5 Media Element API / JS injection).
     */
    val session: GeckoSession

    // Reactive browser state
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isFullScreen = MutableStateFlow(false)
    val isFullScreen: StateFlow<Boolean> = _isFullScreen.asStateFlow()

    init {
        VRLog.d("[WEB-Browser] Initializing VRBrowserController")

        val sessionSettings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .displayMode(GeckoSessionSettings.DISPLAY_MODE_BROWSER)
            .build()

        session = GeckoSession(sessionSettings)
        setupDelegates()
        session.open(runtime)
    }

    private fun setupDelegates() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                permissions: List<GeckoSession.PermissionDelegate.ContentPermission>
            ) {
                _currentUrl.value = url ?: ""
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                _canGoBack.value = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                _canGoForward.value = canGoForward
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                _isLoading.value = true
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _isLoading.value = false
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                _pageTitle.value = title ?: ""
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                _isFullScreen.value = fullScreen
            }
        }
    }

    /**
     * Loads a URL in the browser.
     * Supports direct navigation to HTTP/HTTPS URLs and internal pages (e.g., about:blank).
     */
    fun loadUrl(url: String) {
        val formattedUrl = when {
            url.isBlank() -> "about:blank"
            !url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("about:") -> "https://$url"
            else -> url
        }
        session.loadUri(formattedUrl)
    }

    /**
     * Goes back in the web session's navigation history.
     */
    fun goBack() {
        if (_canGoBack.value) {
            session.goBack()
        }
    }

    /**
     * Goes forward in the web session's navigation history.
     */
    fun goForward() {
        if (_canGoForward.value) {
            session.goForward()
        }
    }

    /**
     * Reloads the active web page.
     */
    fun reload() {
        session.reload()
    }

    /**
     * Closes the browser session and releases GeckoSession resources.
     */
    fun closeSession() {
        VRLog.d("[WEB-Browser] Closing GeckoSession")
        session.close()
    }
}
