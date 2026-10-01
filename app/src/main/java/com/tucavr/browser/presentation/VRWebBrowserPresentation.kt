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
import com.tucavr.history.AppDatabase
import com.tucavr.presentation.BaseComposePresentation
import org.mozilla.geckoview.GeckoSession

/**
 * Fallback factory for creating WebBrowserViewModel when external dependency injection is unavailable.
 */
class WebBrowserViewModelFactory(
    private val mediaController: BrowserMediaController,
    private val bookmarkManager: BrowserBookmarkManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WebBrowserViewModel::class.java)) {
            return WebBrowserViewModel(mediaController, bookmarkManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

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
                    createFallbackViewModel()
                }
            } else {
                createFallbackViewModel()
            }
    }

    private fun createFallbackViewModel(): WebBrowserViewModel {
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
        browserController.loadUrl("about:blank")
    }

    override fun dismiss() {
        browserController.closeSession()
        super.dismiss()
    }
}
