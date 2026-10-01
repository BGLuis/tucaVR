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
import android.widget.EditText
import android.widget.LinearLayout
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.browser.data.BrowserBookmark
import com.tucavr.browser.engine.VRBrowserController
import com.tucavr.designsystem.KeyboardBinding
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.history.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoView

/**
 * VR browser presentation running inside the 2560x1440 [VirtualDisplay].
 *
 * Layout:
 * - Top navigation bar (Back, Forward, Reload, Address Bar, Bookmark Toggle, Geometry Toggle, Close).
 * - Central [GeckoView] viewport filling the rest of the screen.
 * - Integration with the Horizon OS native virtual keyboard through [nativeKeyboardProxy] via [KeyboardBinding].
 * - Bookmark persistence using [BrowserBookmarkDao].
 */
class VRBrowserPresentation(
    private val activity: VRActivity,
    display: Display,
    private val browserController: VRBrowserController,
    context: Context = activity
) : Presentation(context, display) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bookmarkDao by lazy { AppDatabase.getInstance(activity).browserBookmarkDao() }

    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var addressBar: EditText
    private lateinit var bookmarkButton: VoidIconButton
    private lateinit var geometryButton: VoidIconButton
    private lateinit var geckoView: GeckoView

    private var isCurvedGeometry = false
    private var isCurrentUrlBookmarked = false
    private var currentBookmarkEntry: BrowserBookmark? = null

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
        buildGeckoViewport()

        setContentView(rootLayout)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        observeBrowserState()
    }

    private fun buildToolbar() {
        toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                80
            )
        }

        // Back button
        val backButton = VoidIconButton(context, R.drawable.ic_sort_asc, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Back"
            rotation = -90f
            setOnClickListener { browserController.goBack() }
        }

        // Forward button
        val forwardButton = VoidIconButton(context, R.drawable.ic_sort_asc, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Forward"
            rotation = 90f
            setOnClickListener { browserController.goForward() }
        }

        // Reload button
        val reloadButton = VoidIconButton(context, R.drawable.ic_sort_asc, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Reload"
            setOnClickListener { browserController.reload() }
        }

        // Address bar
        addressBar = EditText(context).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(16, 0, 16, 0)
            }
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Enter an address or search the web..."
            setPadding(24, 0, 24, 0)
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

            val keyboardBinding = object : KeyboardBinding {
                override fun currentText(): CharSequence = text.toString()
                override fun onKeyboardText(text: CharSequence, selection: Int) {
                    setText(text)
                    setSelection(selection.coerceIn(0, text.length))
                }
                override fun onImeAction(actionId: Int) {
                    val url = text.toString().trim()
                    if (url.isNotEmpty()) {
                        browserController.loadUrl(url)
                    }
                    activity.hideNativeKeyboard()
                }
                override val inputType: Int = this@apply.inputType
                override val imeOptions: Int = this@apply.imeOptions
            }

            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    activity.showNativeKeyboardFor(keyboardBinding)
                } else {
                    activity.hideNativeKeyboard()
                }
            }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    keyboardBinding.onImeAction(actionId)
                    true
                } else false
            }
        }

        // Bookmark toggle
        bookmarkButton = VoidIconButton(context, R.drawable.ic_search, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Bookmark"
            setOnClickListener { toggleBookmark() }
        }

        // Geometry toggle (flat quad / curved cylinder)
        geometryButton = VoidIconButton(context, R.drawable.ic_folder, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Geometry"
            setOnClickListener { toggleGeometry() }
        }

        // Close browser button
        val closeButton = VoidIconButton(context, R.drawable.ic_warning, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Close"
            setOnClickListener { closeBrowser() }
        }

        toolbar.addView(backButton)
        toolbar.addView(forwardButton)
        toolbar.addView(reloadButton)
        toolbar.addView(addressBar)
        toolbar.addView(bookmarkButton)
        toolbar.addView(geometryButton)
        toolbar.addView(closeButton)

        rootLayout.addView(toolbar)
    }

    private fun buildGeckoViewport() {
        geckoView = GeckoView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        geckoView.setSession(browserController.session)
        rootLayout.addView(geckoView)
    }

    private fun observeBrowserState() {
        scope.launch {
            browserController.currentUrl.collectLatest { url ->
                if (addressBar.text.toString() != url) {
                    addressBar.setText(url)
                }
                checkIfBookmarked(url)
            }
        }
    }

    private fun checkIfBookmarked(url: String) {
        if (url.isBlank() || url == "about:blank") {
            isCurrentUrlBookmarked = false
            currentBookmarkEntry = null
            updateBookmarkButtonState()
            return
        }

        scope.launch(Dispatchers.IO) {
            val bookmarks = bookmarkDao.getAllBookmarks()
            val match = bookmarks.firstOrNull { it.url == url }
            withContext(Dispatchers.Main) {
                isCurrentUrlBookmarked = match != null
                currentBookmarkEntry = match
                updateBookmarkButtonState()
            }
        }
    }

    private fun toggleBookmark() {
        val currentUrl = browserController.currentUrl.value
        if (currentUrl.isBlank() || currentUrl == "about:blank") return

        scope.launch(Dispatchers.IO) {
            if (isCurrentUrlBookmarked && currentBookmarkEntry != null) {
                bookmarkDao.deleteBookmark(currentBookmarkEntry!!)
                withContext(Dispatchers.Main) {
                    isCurrentUrlBookmarked = false
                    currentBookmarkEntry = null
                    updateBookmarkButtonState()
                }
            } else {
                val newBookmark = BrowserBookmark(
                    title = browserController.pageTitle.value.ifBlank { currentUrl },
                    url = currentUrl,
                    isPreset = false
                )
                val id = bookmarkDao.insertBookmark(newBookmark)
                val inserted = newBookmark.copy(id = id)
                withContext(Dispatchers.Main) {
                    isCurrentUrlBookmarked = true
                    currentBookmarkEntry = inserted
                    updateBookmarkButtonState()
                }
            }
        }
    }

    private fun updateBookmarkButtonState() {
        bookmarkButton.alpha = if (isCurrentUrlBookmarked) 1.0f else 0.5f
    }

    private fun toggleGeometry() {
        isCurvedGeometry = !isCurvedGeometry
        geometryButton.alpha = if (isCurvedGeometry) 1.0f else 0.6f
        activity.setBrowserGeometryMode(isCurvedGeometry)
    }

    private fun closeBrowser() {
        activity.closeBrowserSession()
    }

    /**
     * Scrolls the browser view by the delta received from the VR controls.
     */
    fun dispatchScroll(scrollDeltaY: Float) {
        if (::geckoView.isInitialized) {
            geckoView.scrollBy(0, scrollDeltaY.toInt())
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }
}
