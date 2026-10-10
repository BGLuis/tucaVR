package com.tucavr.presentation

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tucavr.debug.VRLog

/**
 * Generic, self-contained presentation base for hosting Jetpack Compose in android.app.Presentation.
 * Implements [LifecycleOwner], [ViewModelStoreOwner], and [SavedStateRegistryOwner], managing the
 * native Dialog lifecycle (onStart, onStop, dismiss) without leaking memory through decorView.
 */
abstract class BaseComposePresentation(
    context: Context,
    display: Display,
    theme: Int = android.R.style.Theme_NoTitleBar_Fullscreen,
    parentLifecycleOwner: LifecycleOwner? = null,
    private val parentViewModelStoreOwner: ViewModelStoreOwner? = null,
    parentSavedStateRegistryOwner: SavedStateRegistryOwner? = null
) : Presentation(context, display, theme),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private val effectiveLifecycleOwner: LifecycleOwner = parentLifecycleOwner ?: this
    private val effectiveViewModelStoreOwner: ViewModelStoreOwner = parentViewModelStoreOwner ?: this
    private val effectiveSavedStateRegistryOwner: SavedStateRegistryOwner = parentSavedStateRegistryOwner ?: this

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = parentViewModelStoreOwner?.viewModelStore ?: store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    @Composable
    abstract fun PresentationContent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VRLog.d("${getTag()} Creating presentation")
        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        val decorView = window?.decorView
        if (decorView != null) {
            decorView.setViewTreeLifecycleOwner(effectiveLifecycleOwner)
            decorView.setViewTreeViewModelStoreOwner(effectiveViewModelStoreOwner)
            decorView.setViewTreeSavedStateRegistryOwner(effectiveSavedStateRegistryOwner)
        }

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PresentationContent()
            }
        }

        setContentView(composeView)
    }

    override fun onStart() {
        super.onStart()
        VRLog.d("${getTag()} Starting presentation")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStop() {
        VRLog.d("${getTag()} Stopping presentation")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onSaveInstanceState(): Bundle {
        val bundle = super.onSaveInstanceState()
        savedStateRegistryController.performSave(bundle)
        return bundle
    }

    override fun dismiss() {
        VRLog.d("${getTag()} Dismissing presentation")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(null)
            decorView.setViewTreeViewModelStoreOwner(null)
            decorView.setViewTreeSavedStateRegistryOwner(null)
        }
        store.clear()
        super.dismiss()
    }

    private fun getTag(): String {
        val parentClass = javaClass.superclass?.simpleName ?: "BaseComposePresentation"
        val currentClass = javaClass.simpleName
        return "[$parentClass:$currentClass]"
    }
}
