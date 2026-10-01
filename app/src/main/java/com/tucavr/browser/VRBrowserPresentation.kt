package com.tucavr.browser

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.tucavr.VRActivity
import com.tucavr.browser.data.BrowserBookmark
import com.tucavr.browser.domain.BrowserBookmarkManager
import com.tucavr.browser.engine.VRBrowserController
import com.tucavr.browser.ui.BrowserToolbarView
import com.tucavr.browser.ui.EditBookmarkModalView
import com.tucavr.browser.ui.ShortcutsPanelView
import com.tucavr.designsystem.KeyboardBinding
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidTheme
import com.tucavr.history.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * VR browser presentation running inside the 2560x1440 [VirtualDisplay].
 *
 * Modular architecture:
 * - UI orchestrator connecting [BrowserToolbarView], [ShortcutsPanelView], and [EditBookmarkModalView].
 * - State management and persistence through [BrowserBookmarkManager].
 * - Full support for the Horizon OS native virtual keyboard via [KeyboardBinding].
 * - Central [GeckoView] viewport.
 */
class VRBrowserPresentation(
    private val activity: VRActivity,
    display: Display,
    private val browserController: VRBrowserController,
    context: Context = activity
) : Presentation(context, display) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bookmarkManager by lazy {
        BrowserBookmarkManager(AppDatabase.getInstance(activity).browserBookmarkDao(), scope)
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbarView: BrowserToolbarView
    private lateinit var contentContainer: FrameLayout
    private lateinit var geckoView: GeckoView
    private lateinit var exitFullscreenButton: View
    private lateinit var shortcutsPanelView: ShortcutsPanelView
    private lateinit var editBookmarkModalView: EditBookmarkModalView

    private var isCurvedGeometry = false
    private var isCurrentUrlBookmarked = false

    private fun dpToPx(dp: Float): Int = VoidTheme.dpToPx(context, dp)

    private val webKeyboardBinding by lazy {
        object : KeyboardBinding {
            private var webText = ""
            override fun currentText(): CharSequence = webText
            override fun onKeyboardText(text: CharSequence, selection: Int) {
                val newStr = text.toString()
                if (newStr.length > webText.length) {
                    val added = newStr.substring(webText.length)
                    for (c in added) {
                        val downEvent = android.view.KeyEvent(
                            android.os.SystemClock.uptimeMillis(),
                            c.toString(),
                            0,
                            0
                        )
                        geckoView.dispatchKeyEvent(downEvent)
                    }
                } else if (newStr.length < webText.length) {
                    val diff = webText.length - newStr.length
                    for (i in 0 until diff) {
                        val delDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL)
                        val delUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL)
                        geckoView.dispatchKeyEvent(delDown)
                        geckoView.dispatchKeyEvent(delUp)
                    }
                }
                webText = newStr
            }

            override fun onImeAction(actionId: Int) {
                val enterDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
                val enterUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER)
                geckoView.dispatchKeyEvent(enterDown)
                geckoView.dispatchKeyEvent(enterUp)
                activity.hideNativeKeyboard()
            }

            override val inputType: Int = InputType.TYPE_CLASS_TEXT
            override val imeOptions: Int = EditorInfo.IME_ACTION_DONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
        }

        buildToolbar()
        buildContentContainer()
        buildEditBookmarkModal()

        setContentView(rootLayout)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        observeBrowserState()
        observeBookmarks()
    }

    private fun buildToolbar() {
        toolbarView = BrowserToolbarView(
            context = context,
            onBackClicked = { browserController.goBack() },
            onForwardClicked = { browserController.goForward() },
            onReloadClicked = { browserController.reload() },
            onHomeClicked = { navigateToHome() },
            onBookmarkToggleClicked = { toggleBookmark() },
            onGeometryToggleClicked = { toggleGeometry() },
            onCloseClicked = { closeBrowser() },
            onUrlSubmitted = { url -> openUrl(url) },
            onShowKeyboardRequested = { binding -> activity.showNativeKeyboardFor(binding) },
            onHideKeyboardRequested = { activity.hideNativeKeyboard() }
        )
        rootLayout.addView(toolbarView)
    }

    private fun buildContentContainer() {
        contentContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // Viewport GeckoView
        geckoView = GeckoView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
        }
        geckoView.setSession(browserController.session)
        contentContainer.addView(geckoView)

        // Configure TextInputDelegate to open the native keyboard only for web text fields
        browserController.session.textInput.setDelegate(object : GeckoSession.TextInputDelegate {
            override fun showSoftInput(session: GeckoSession) {
                activity.showNativeKeyboardFor(webKeyboardBinding)
            }

            override fun hideSoftInput(session: GeckoSession) {
                activity.hideNativeKeyboard()
            }
        })

        // Floating button to exit fullscreen mode
        exitFullscreenButton = VoidButton(context, VoidButtonStyle.SECONDARY, isCompact = true).apply {
            text = "✕ Exit Fullscreen"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dpToPx(16f), dpToPx(16f), 0)
            }
            visibility = View.GONE
            setOnClickListener {
                browserController.session.exitFullScreen()
            }
        }
        contentContainer.addView(exitFullscreenButton)

        // Shortcuts and bookmarks panel (home screen)
        shortcutsPanelView = ShortcutsPanelView(
            context = context,
            onBookmarkClicked = { bookmark -> openUrl(bookmark.url) },
            onAddBookmarkClicked = { showEditBookmarkModal(null) },
            onEditBookmarkClicked = { bookmark -> showEditBookmarkModal(bookmark) },
            onDeleteBookmarkClicked = { bookmark -> bookmarkManager.deleteBookmark(bookmark) },
            onReorderRequested = { from, to -> bookmarkManager.reorderBookmark(from, to) }
        )
        contentContainer.addView(shortcutsPanelView)

        rootLayout.addView(contentContainer)
    }

    private fun buildEditBookmarkModal() {
        editBookmarkModalView = EditBookmarkModalView(
            context = context,
            onSaveRequested = { editingBookmark, title, url ->
                if (editingBookmark != null) {
                    bookmarkManager.updateBookmark(editingBookmark, title, url)
                } else {
                    bookmarkManager.addBookmark(title, url)
                }
            },
            onDismissRequested = { activity.hideNativeKeyboard() },
            onShowKeyboardRequested = { binding -> activity.showNativeKeyboardFor(binding) },
            onHideKeyboardRequested = { activity.hideNativeKeyboard() }
        )
        contentContainer.addView(editBookmarkModalView)
    }

    private fun showEditBookmarkModal(bookmark: BrowserBookmark?) {
        editBookmarkModalView.show(bookmark)
    }

    private fun observeBookmarks() {
        scope.launch {
            bookmarkManager.bookmarks.collectLatest { list ->
                shortcutsPanelView.updateBookmarks(list)
            }
        }
    }

    fun navigateToHome() {
        toolbarView.setUrl("about:blank")
        browserController.loadUrl("about:blank")
        shortcutsPanelView.visibility = View.VISIBLE
        geckoView.visibility = View.GONE
    }

    private fun openUrl(url: String) {
        val finalUrl = bookmarkManager.normalizeUrl(url)
        if (finalUrl.isEmpty()) return
        if (finalUrl == "about:blank") {
            navigateToHome()
            return
        }

        toolbarView.setUrl(finalUrl)
        browserController.loadUrl(finalUrl)
        shortcutsPanelView.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
    }

    private fun observeBrowserState() {
        scope.launch {
            browserController.currentUrl.collectLatest { url ->
                if (url == "about:blank" || url.isBlank()) {
                    shortcutsPanelView.visibility = View.VISIBLE
                    geckoView.visibility = View.GONE
                } else {
                    shortcutsPanelView.visibility = View.GONE
                    geckoView.visibility = View.VISIBLE
                }

                toolbarView.setUrl(url)
                checkIfBookmarked(url)
            }
        }

        scope.launch {
            browserController.isFullScreen.collectLatest { isFull ->
                if (isFull) {
                    toolbarView.visibility = View.GONE
                    exitFullscreenButton.visibility = View.VISIBLE
                } else {
                    toolbarView.visibility = View.VISIBLE
                    exitFullscreenButton.visibility = View.GONE
                }
                geckoView.requestFocus()
                geckoView.requestLayout()
                geckoView.invalidate()
            }
        }
    }

    private fun checkIfBookmarked(url: String) {
        if (url.isBlank() || url == "about:blank") {
            isCurrentUrlBookmarked = false
            toolbarView.setIsBookmarked(false)
            return
        }

        scope.launch {
            val bookmark = bookmarkManager.findBookmarkByUrl(url)
            isCurrentUrlBookmarked = bookmark != null
            toolbarView.setIsBookmarked(isCurrentUrlBookmarked)
        }
    }

    private fun toggleBookmark() {
        val currentUrl = browserController.currentUrl.value
        val pageTitle = browserController.pageTitle.value
        bookmarkManager.toggleBookmark(currentUrl, pageTitle) { bookmarked, _ ->
            isCurrentUrlBookmarked = bookmarked
            toolbarView.setIsBookmarked(bookmarked)
        }
    }

    private fun toggleGeometry() {
        isCurvedGeometry = !isCurvedGeometry
        toolbarView.setIsCurvedGeometry(isCurvedGeometry)
        activity.setBrowserGeometryMode(isCurvedGeometry)
    }

    private fun closeBrowser() {
        activity.closeBrowserSession()
    }

    /**
     * Scrolls the browser view or shortcuts panel by the delta received from the VR controls.
     */
    fun dispatchScroll(scrollDeltaY: Float) {
        if (shortcutsPanelView.visibility == View.VISIBLE) {
            shortcutsPanelView.scrollBy(0, -scrollDeltaY.toInt())
        } else if (::geckoView.isInitialized) {
            geckoView.scrollBy(0, scrollDeltaY.toInt())
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }
}
