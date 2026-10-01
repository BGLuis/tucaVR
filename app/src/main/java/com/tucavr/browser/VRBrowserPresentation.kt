package com.tucavr.browser

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.R
import com.tucavr.VRActivityotão Início (Home)
import com.tucavr.browser.data.BrowserBookmark
import com.tucavr.browser.engine.VRBrowserController
import com.tucavr.designsystem.KeyboardBinding
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.history.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * VR browser presentation running inside the 2560x1440 [VirtualDisplay].
 *
 * Features:
 * - Top navigation bar (Back, Forward, Reload, Home, Address Bar, Bookmark Toggle, Geometry Toggle, Close).
 * - Visual shortcuts and bookmarks panel with cards for YouTube, Netflix, Disney+, Prime Video, Twitch, and user-created entries.
 * - VR overlay for creating, editing, deleting, and reordering bookmarks.
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
    private val bookmarkDao by lazy { AppDatabase.getInstance(activity).browserBookmarkDao() }

    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var addressBar: EditText
    private lateinit var bookmarkButton: VoidIconButton
    private lateinit var geometryButton: VoidIconButton
    private lateinit var contentContainer: FrameLayout
    private lateinit var geckoView: GeckoView
    private lateinit var exitFullscreenButton: View

    // Shortcuts and bookmarks UI
    private lateinit var shortcutsPanel: ScrollView
    private lateinit var shortcutsGrid: GridLayout
    private var cachedBookmarks: List<BrowserBookmark> = emptyList()

    // Bookmark editing modal
    private lateinit var editBookmarkModal: FrameLayout
    private lateinit var modalTitleView: TextView
    private lateinit var modalTitleInput: EditText
    private lateinit var modalUrlInput: EditText
    private var editingBookmark: BrowserBookmark? = null

    private var isCurvedGeometry = false
    private var isCurrentUrlBookmarked = false
    private var currentBookmarkEntry: BrowserBookmark? = null

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
        toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(8f))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val buttonParams = LinearLayout.LayoutParams(dpToPx(44f), dpToPx(44f)).apply {
            setMargins(dpToPx(4f), 0, dpToPx(4f), 0)
        }

        // Back button
        val backButton = VoidIconButton(context, R.drawable.ic_browser_back, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Back"
            layoutParams = buttonParams
            setOnClickListener { browserController.goBack() }
        }

        // Forward button
        val forwardButton = VoidIconButton(context, R.drawable.ic_browser_forward, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Forward"
            layoutParams = buttonParams
            setOnClickListener { browserController.goForward() }
        }

        // Reload button
        val reloadButton = VoidIconButton(context, R.drawable.ic_browser_refresh, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Reload"
            layoutParams = buttonParams
            setOnClickListener { browserController.reload() }
        }

        // Return to home
        val homeButton = VoidIconButton(context, R.drawable.ic_browser_home, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Home"
            layoutParams = buttonParams
            setOnClickListener { navigateToHome() }
        }

        // Address bar
        addressBar = EditText(context).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(44f), 1f).apply {
                setMargins(dpToPx(8f), 0, dpToPx(8f), 0)
            }
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Enter an address or search the web..."
            setPadding(dpToPx(16f), 0, dpToPx(16f), 0)
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
                        openUrl(url)
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
        bookmarkButton = VoidIconButton(context, R.drawable.ic_browser_bookmark, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Bookmark"
            layoutParams = buttonParams
            setOnClickListener { toggleBookmark() }
        }

        // Geometry toggle (flat quad / curved cylinder)
        geometryButton = VoidIconButton(context, R.drawable.ic_browser_geometry, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Geometry"
            layoutParams = buttonParams
            setOnClickListener { toggleGeometry() }
        }

        // Close browser button
        val closeButton = VoidIconButton(context, R.drawable.ic_browser_close, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Close"
            layoutParams = buttonParams
            setOnClickListener { closeBrowser() }
        }

        toolbar.addView(backButton)
        toolbar.addView(forwardButton)
        toolbar.addView(reloadButton)
        toolbar.addView(homeButton)
        toolbar.addView(addressBar)
        toolbar.addView(bookmarkButton)
        toolbar.addView(geometryButton)
        toolbar.addView(closeButton)

        rootLayout.addView(toolbar)
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

        // Configure TextInputDelegate
        browserController.session.textInput.setDelegate(object : GeckoSession.TextInputDelegate {
            override fun showSoftInput(session: GeckoSession) {
                activity.showNativeKeyboardFor(webKeyboardBinding)
            }

            override fun hideSoftInput(session: GeckoSession) {
                activity.hideNativeKeyboard()
            }
        })

        // Addition exit button from fullscreen
        exitFullscreenButton = VoidButton(context, VoidButtonStyle.SECONDARY, isCompact = true).apply {
            text = "✕ Sair da Tela Cheia"
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
        buildShortcutsPanel()
        contentContainer.addView(shortcutsPanel)

        rootLayout.addView(contentContainer)
    }

    private fun buildShortcutsPanel() {
        shortcutsPanel = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
            visibility = View.VISIBLE // Shown by default on launch (home screen)
        }

        val innerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(20f), dpToPx(24f), dpToPx(24f))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Home / shortcuts header
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(16f))
        }

        val titleTextLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val mainTitle = VoidText.title(context, "Quick Links & Bookmarks", sizeSp = 24f)
        val subTitle = VoidText.mono(context, "Access your favorite streaming services or add new shortcuts in VR", sizeSp = 13f, secondary = true)

        titleTextLayout.addView(mainTitle)
        titleTextLayout.addView(subTitle)

        val addShortcutButton = VoidButton(context, VoidButtonStyle.PRIMARY, isCompact = true).apply {
            text = "+ New Bookmark"
            setOnClickListener { showEditBookmarkModal(null) }
        }

        headerLayout.addView(titleTextLayout)
        headerLayout.addView(addShortcutButton)

        innerContainer.addView(headerLayout)

        // Card grid
        shortcutsGrid = GridLayout(context).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_MARGINS
            useDefaultMargins = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        innerContainer.addView(shortcutsGrid)
        shortcutsPanel.addView(innerContainer)
    }

    private fun buildEditBookmarkModal() {
        editBookmarkModal = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#CC000000"))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        val dialogBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(24f), dpToPx(24f), dpToPx(24f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = VoidTheme.dp(context, 16f)
                setStroke(VoidTheme.dpToPx(context, 2f), VoidTheme.colorAccent)
            }
            layoutParams = FrameLayout.LayoutParams(dpToPx(480f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }

        modalTitleView = VoidText.title(context, "Add Bookmark", sizeSp = 20f)
        dialogBox.addView(modalTitleView)

        // Title field
        val titleLabel = VoidText.body(context, "Title:", sizeSp = 14f).apply {
            setPadding(0, dpToPx(12f), 0, dpToPx(6f))
        }
        dialogBox.addView(titleLabel)

        modalTitleInput = EditText(context).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(44f))
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "e.g., YouTube, Netflix..."
            setPadding(dpToPx(12f), 0, dpToPx(12f), 0)
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT

            val titleBinding = object : KeyboardBinding {
                override fun currentText(): CharSequence = text.toString()
                override fun onKeyboardText(text: CharSequence, selection: Int) {
                    setText(text)
                    setSelection(selection.coerceIn(0, text.length))
                }
                override fun onImeAction(actionId: Int) {
                    modalUrlInput.requestFocus()
                }
                override val inputType: Int = this@apply.inputType
                override val imeOptions: Int = this@apply.imeOptions
            }

            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    activity.showNativeKeyboardFor(titleBinding)
                }
            }
        }
        dialogBox.addView(modalTitleInput)

        // URL field
        val urlLabel = VoidText.body(context, "Web Address (URL):", sizeSp = 14f).apply {
            setPadding(0, dpToPx(12f), 0, dpToPx(6f))
        }
        dialogBox.addView(urlLabel)

        modalUrlInput = EditText(context).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(44f))
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "https://..."
            setPadding(dpToPx(12f), 0, dpToPx(12f), 0)
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

            val urlBinding = object : KeyboardBinding {
                override fun currentText(): CharSequence = text.toString()
                override fun onKeyboardText(text: CharSequence, selection: Int) {
                    setText(text)
                    setSelection(selection.coerceIn(0, text.length))
                }
                override fun onImeAction(actionId: Int) {
                    saveBookmarkFromModal()
                }
                override val inputType: Int = this@apply.inputType
                override val imeOptions: Int = this@apply.imeOptions
            }

            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    activity.showNativeKeyboardFor(urlBinding)
                }
            }
        }
        dialogBox.addView(modalUrlInput)

        // Modal action buttons
        val actionsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dpToPx(18f), 0, 0)
        }

        val cancelButton = VoidButton(context, VoidButtonStyle.SECONDARY, isCompact = true).apply {
            text = "Cancel"
            setOnClickListener { hideEditBookmarkModal() }
        }

        val saveButton = VoidButton(context, VoidButtonStyle.PRIMARY, isCompact = true).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpToPx(12f) }
            setOnClickListener { saveBookmarkFromModal() }
        }

        actionsLayout.addView(cancelButton)
        actionsLayout.addView(saveButton)
        dialogBox.addView(actionsLayout)

        editBookmarkModal.addView(dialogBox)
        contentContainer.addView(editBookmarkModal)
    }

    private fun showEditBookmarkModal(bookmark: BrowserBookmark?) {
        editingBookmark = bookmark
        if (bookmark != null) {
            modalTitleView.text = "Edit Bookmark"
            modalTitleInput.setText(bookmark.title)
            modalUrlInput.setText(bookmark.url)
        } else {
            modalTitleView.text = "New Bookmark"
            modalTitleInput.setText("")
            modalUrlInput.setText("https://")
        }
        editBookmarkModal.visibility = View.VISIBLE
        modalTitleInput.requestFocus()
    }

    private fun hideEditBookmarkModal() {
        activity.hideNativeKeyboard()
        editBookmarkModal.visibility = View.GONE
        editingBookmark = null
    }

    private fun saveBookmarkFromModal() {
        val title = modalTitleInput.text.toString().trim()
        var url = modalUrlInput.text.toString().trim()

        if (url.isEmpty() || url == "https://") return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        val finalTitle = title.ifBlank { url }

        scope.launch(Dispatchers.IO) {
            val target = editingBookmark
            if (target != null) {
                bookmarkDao.updateBookmark(target.copy(title = finalTitle, url = url))
            } else {
                val newOrder = cachedBookmarks.size
                bookmarkDao.insertBookmark(
                    BrowserBookmark(
                        title = finalTitle,
                        url = url,
                        displayOrder = newOrder,
                        isPreset = false
                    )
                )
            }
            withContext(Dispatchers.Main) {
                hideEditBookmarkModal()
            }
        }
    }

    private fun observeBookmarks() {
        scope.launch {
            bookmarkDao.getAllBookmarksFlow().collectLatest { bookmarks ->
                if (bookmarks.isEmpty()) {
                    // Seed default bookmarks if the table is empty
                    withContext(Dispatchers.IO) {
                        bookmarkDao.insertAll(AppDatabase.DEFAULT_PRESETS)
                    }
                } else {
                    cachedBookmarks = bookmarks
                    updateShortcutsGrid(bookmarks)
                }
            }
        }
    }

    private fun updateShortcutsGrid(bookmarks: List<BrowserBookmark>) {
        shortcutsGrid.removeAllViews()

        bookmarks.forEachIndexed { index, bookmark ->
            val cardView = buildShortcutCard(bookmark, index, bookmarks.size)
            shortcutsGrid.addView(cardView)
        }
    }

    private fun buildShortcutCard(bookmark: BrowserBookmark, index: Int, totalCount: Int): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14f), dpToPx(14f), dpToPx(14f), dpToPx(14f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = VoidTheme.dp(context, 12f)
                setStroke(
                    VoidTheme.dpToPx(context, 1f),
                    if (bookmark.isPreset) VoidTheme.colorAccent else Color.parseColor("#333333")
                )
            }
            val params = GridLayout.LayoutParams().apply {
                width = dpToPx(276f)
                height = dpToPx(150f)
                setMargins(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
            }
            layoutParams = params
            isClickable = true
            isFocusable = true

            setOnClickListener {
                openUrl(bookmark.url)
            }
        }

        // Title and preset badge
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = VoidText.body(context, bookmark.title, sizeSp = 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleView)

        if (bookmark.isPreset) {
            val presetBadge = TextView(context).apply {
                text = "DEFAULT"
                textSize = 10f
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f))
                background = GradientDrawable().apply {
                    setColor(VoidTheme.colorAccent)
                    cornerRadius = VoidTheme.dp(context, 4f)
                }
            }
            headerRow.addView(presetBadge)
        }

        card.addView(headerRow)

        // URL display
        val urlView = VoidText.mono(context, bookmark.url, sizeSp = 12f, secondary = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dpToPx(4f), 0, 0)
        }
        card.addView(urlView)

        // Spacer to push the action buttons to the bottom of the card
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        card.addView(spacer)

        // Card action bar (reorder, edit, delete)
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Move left / up
        val moveLeftBtn = VoidIconButton(context, R.drawable.ic_sort_asc, VoidButtonStyle.SECONDARY, isCircular = false).apply {
            rotation = -90f
            layoutParams = LinearLayout.LayoutParams(dpToPx(32f), dpToPx(32f)).apply { marginEnd = dpToPx(6f) }
            isEnabled = index > 0
            alpha = if (index > 0) 1.0f else 0.3f
            setOnClickListener {
                reorderBookmark(index, index - 1)
            }
        }

        // Move right / down
        val moveRightBtn = VoidIconButton(context, R.drawable.ic_sort_asc, VoidButtonStyle.SECONDARY, isCircular = false).apply {
            rotation = 90f
            layoutParams = LinearLayout.LayoutParams(dpToPx(32f), dpToPx(32f)).apply { marginEnd = dpToPx(12f) }
            isEnabled = index < totalCount - 1
            alpha = if (index < totalCount - 1) 1.0f else 0.3f
            setOnClickListener {
                reorderBookmark(index, index + 1)
            }
        }

        val actionSpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        // Edit
        val editBtn = VoidIconButton(context, R.drawable.ic_search, VoidButtonStyle.SECONDARY, isCircular = false).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(32f), dpToPx(32f)).apply { marginEnd = dpToPx(6f) }
            setOnClickListener {
                showEditBookmarkModal(bookmark)
            }
        }

        // Delete
        val deleteBtn = VoidIconButton(context, R.drawable.ic_warning, VoidButtonStyle.SECONDARY, isCircular = false).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(32f), dpToPx(32f))
            setOnClickListener {
                deleteBookmark(bookmark)
            }
        }

        actionsRow.addView(moveLeftBtn)
        actionsRow.addView(moveRightBtn)
        actionsRow.addView(actionSpacer)
        actionsRow.addView(editBtn)
        actionsRow.addView(deleteBtn)

        card.addView(actionsRow)

        return card
    }

    private fun reorderBookmark(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in cachedBookmarks.indices || toIndex !in cachedBookmarks.indices) return
        val mutableList = cachedBookmarks.toMutableList()
        val item = mutableList.removeAt(fromIndex)
        mutableList.add(toIndex, item)

        scope.launch(Dispatchers.IO) {
            bookmarkDao.reorderBookmarks(mutableList.map { it.id })
        }
    }

    private fun deleteBookmark(bookmark: BrowserBookmark) {
        scope.launch(Dispatchers.IO) {
            bookmarkDao.deleteBookmark(bookmark)
        }
    }

    private fun navigateToHome() {
        addressBar.setText("about:blank")
        shortcutsPanel.visibility = View.VISIBLE
        geckoView.visibility = View.GONE
    }

    private fun openUrl(url: String) {
        var finalUrl = url.trim()
        if (finalUrl.isEmpty()) return
        if (finalUrl == "about:blank") {
            navigateToHome()
            return
        }

        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://") && !finalUrl.startsWith("about:")) {
            finalUrl = "https://$finalUrl"
        }

        addressBar.setText(finalUrl)
        browserController.loadUrl(finalUrl)
        shortcutsPanel.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
    }

    private fun observeBrowserState() {
        scope.launch {
            browserController.currentUrl.collectLatest { url ->
                if (url == "about:blank" || url.isBlank()) {
                    shortcutsPanel.visibility = View.VISIBLE
                    geckoView.visibility = View.GONE
                } else {
                    shortcutsPanel.visibility = View.GONE
                    geckoView.visibility = View.VISIBLE
                }

                if (addressBar.text.toString() != url) {
                    addressBar.setText(url)
                }
                checkIfBookmarked(url)
            }
        }

        scope.launch {
            browserController.isFullScreen.collectLatest { isFull ->
                if (isFull) {
                    toolbar.visibility = View.GONE
                    exitFullscreenButton.visibility = View.VISIBLE
                } else {
                    toolbar.visibility = View.VISIBLE
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
                    displayOrder = cachedBookmarks.size,
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
        if (shortcutsPanel.visibility == View.VISIBLE) {
            shortcutsPanel.scrollBy(0, -scrollDeltaY.toInt())
        } else if (::geckoView.isInitialized) {
            geckoView.scrollBy(0, scrollDeltaY.toInt())
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }
}
