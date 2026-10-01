package com.tucavr.browser.presentation

import android.content.Context
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.SavedStateRegistryOwner
import com.tucavr.VRActivity
import com.tucavr.browser.controller.BrowserMediaController
import com.tucavr.browser.domain.BrowserBookmarkManager
import com.tucavr.browser.engine.VRBrowserController
import com.tucavr.browser.ui.WebBrowserScreen
import com.tucavr.browser.viewmodel.WebBrowserViewModel
import com.tucavr.browser.viewmodel.WebBrowserViewModelFactory
import com.tucavr.debug.VRLog
import com.tucavr.history.AppDatabase
import com.tucavr.presentation.BaseComposePresentation
import org.mozilla.geckoview.GeckoSession


/**
 * Concrete Presentation dialog for the VR 2D Web Browser panel.
 * Collects ViewModel state and renders the WebBrowserScreen Compose component with GeckoView session.
 */
class VRWebBrowserPresentation(
    context: Context,
    display: Display,
    lifecycleOwner: LifecycleOwner? = null,
    viewModelStoreOwner: ViewModelStoreOwner? = null,
    savedStateRegistryOwner: SavedStateRegistryOwner? = null,
    private val browserController: VRBrowserController = VRBrowserController(context),
    private val activity: VRActivity? = context as? VRActivity,
    private val viewModelProvider: (() -> WebBrowserViewModel)? = null
) : BaseComposePresentation(
    context = context,
    display = display,
    parentLifecycleOwner = lifecycleOwner,
    parentViewModelStoreOwner = viewModelStoreOwner,
    parentSavedStateRegistryOwner = savedStateRegistryOwner
) {

    private val viewModel: WebBrowserViewModel by lazy {
        viewModelProvider?.invoke()
            ?: if (viewModelStoreOwner != null) {
                try {
                    ViewModelProvider(viewModelStoreOwner)[WebBrowserViewModel::class.java]
                } catch (e: Exception) {
                    VRLog.e("Failed to create WebBrowserViewModel: ${e.message}")
                    createFallbackViewModel()
                }
            } else {
                createFallbackViewModel()
            }
    }

    private fun createFallbackViewModel(): WebBrowserViewModel {
        VRLog.w("[WEB-Browser] Fallback WebBrowserViewModel created")
        val db = AppDatabase.getInstance(context.applicationContext)
        val bookmarkDao = db.browserBookmarkDao()
        val bookmarkManager = BrowserBookmarkManager(bookmarkDao)
        val mediaController = BrowserMediaController()
        val factory = WebBrowserViewModelFactory(mediaController, bookmarkManager)
        return ViewModelProvider(this, factory)[WebBrowserViewModel::class.java]
    }

    val geckoSession: GeckoSession
        get() = browserController.session

    @Composable
    override fun PresentationContent() {
        VRLog.d("[WEB-Browser] Rendering WebBrowserScreen")
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        WebBrowserScreen(
            state = state,
            mediaCommands = viewModel.mediaCommands,
            geckoSession = geckoSession,
            onShowNativeKeyboard = { binding -> activity?.showNativeKeyboardFor(binding) },
            onHideNativeKeyboard = { activity?.hideNativeKeyboard() },
            onCloseBrowser = { activity?.closeBrowserSession() },
            onToggleGeometry = { isCurved -> activity?.setBrowserGeometryMode(isCurved) },
            onEvent = { event -> viewModel.handleEvent(event) },
            updateWebViewState = { url, isLoading, loadingProgress, pageTitle, canGoBack, canGoForward, isFullScreen, errorMessage ->
                viewModel.updateWebViewState(
                    url = url,
                    isLoading = isLoading,
                    loadingProgress = loadingProgress,
                    pageTitle = pageTitle,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    isFullScreen = isFullScreen,
                    errorMessage = errorMessage
                )
            }
        )
    }

    fun dispatchScroll(scrollDeltaY: Float) {
        geckoSession.loadUri("javascript:window.scrollBy(0, ${scrollDeltaY.toInt()});")
    }

    fun navigateToHome() {
        VRLog.d("[WEB-Browser] Navigating to home")
        browserController.loadUrl("about:blank")
    }

    override fun dismiss() {
        VRLog.d("[WEB-Browser] Dismissing presentation")
        browserController.closeSession()
        super.dismiss()
    }
}
