package com.tucavr.browser.engine

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Main manager for the GeckoView engine and Off-Screen Rendering capture (VirtualDisplay/Surface).
 *
 * Responsibilities:
 * 1. Initialize [GeckoRuntime] with Widevine DRM enabled (`--enable-media-drm`).
 * 2. Configure [GeckoSession] with a tablet/desktop user agent and desktop viewport.
 * 3. Manage the 2560x1440 [ImageReader] / [VirtualDisplay] to capture off-screen frames for Vulkan.
 * 4. Expose navigation, lifecycle, and reactive state ([StateFlow]).
 */
class VRBrowserController(
    private val context: Context,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val densityDpi: Int = DEFAULT_DPI
) {

    companion object {
        const val DEFAULT_WIDTH = 2560
        const val DEFAULT_HEIGHT = 1440
        const val DEFAULT_DPI = 320

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

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
                .build()
            return GeckoRuntime.create(context.applicationContext, settings)
        }
    }

    private val runtime: GeckoRuntime = getOrCreateRuntime(context)
    val session: GeckoSession

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var geckoDisplay: GeckoDisplay? = null

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

    init {
        val sessionSettings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .userAgentOverride(DEFAULT_USER_AGENT)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .build()

        session = GeckoSession(sessionSettings)
        setupDelegates()
        session.open(runtime)
        setupOffscreenDisplay()
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
        }
    }

    private fun setupOffscreenDisplay() {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val surface = imageReader?.surface ?: return

        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        virtualDisplay = displayManager.createVirtualDisplay(
            "VR_Browser_Display",
            width,
            height,
            densityDpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )

        val surfaceInfo = GeckoDisplay.SurfaceInfo.Builder(surface)
            .size(width, height)
            .build()

        geckoDisplay = session.acquireDisplay()
        geckoDisplay?.surfaceChanged(surfaceInfo)
    }

    /**
     * Returns the Android [Surface] produced by the off-screen image reader.
     */
    fun getSurface(): Surface? = imageReader?.surface

    /**
     * Returns the native (ANativeWindow*) pointer for the [Surface] for JNI/Vulkan integration.
     */
    fun getNativeSurfacePointer(): Long {
        val surface = getSurface() ?: return 0L
        return try {
            nativeGetSurfacePointer(surface)
        } catch (e: Throwable) {
            try {
                val field = Surface::class.java.getDeclaredField("mNativeObject")
                field.isAccessible = true
                field.getLong(surface)
            } catch (t: Throwable) {
                0L
            }
        }
    }

    /**
     * Loads a URL in the browser.
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
     * Goes back in the navigation history.
     */
    fun goBack() {
        if (_canGoBack.value) {
            session.goBack()
        }
    }

    /**
     * Goes forward in the navigation history.
     */
    fun goForward() {
        if (_canGoForward.value) {
            session.goForward()
        }
    }

    /**
     * Reloads the current page.
     */
    fun reload() {
        session.reload()
    }

    /**
     * Closes the browser session and releases graphics resources (VirtualDisplay/ImageReader/GeckoSession).
     */
    fun closeSession() {
        geckoDisplay?.surfaceDestroyed()
        geckoDisplay = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        session.close()
    }

    private external fun nativeGetSurfacePointer(surface: Surface): Long
}
