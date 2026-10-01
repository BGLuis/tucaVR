package com.tucavr.browser.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import com.tucavr.R
import com.tucavr.designsystem.KeyboardBinding
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidTheme

/**
 * Modular VR browser top navigation bar (toolbar) component.
 */
class BrowserToolbarView(
    context: Context,
    private val onBackClicked: () -> Unit,
    private val onForwardClicked: () -> Unit,
    private val onReloadClicked: () -> Unit,
    private val onHomeClicked: () -> Unit,
    private val onBookmarkToggleClicked: () -> Unit,
    private val onGeometryToggleClicked: () -> Unit,
    private val onCloseClicked: () -> Unit,
    private val onUrlSubmitted: (String) -> Unit,
    private val onShowKeyboardRequested: (KeyboardBinding) -> Unit,
    private val onHideKeyboardRequested: () -> Unit
) : LinearLayout(context) {

    private lateinit var addressBar: EditText
    private lateinit var bookmarkButton: VoidIconButton
    private lateinit var geometryButton: VoidIconButton

    private fun dpToPx(dp: Float): Int = VoidTheme.dpToPx(context, dp)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(8f))
        setBackgroundColor(Color.parseColor("#1E1E1E"))
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        buildUi()
    }

    private fun buildUi() {
        val buttonParams = LayoutParams(dpToPx(44f), dpToPx(44f)).apply {
            setMargins(dpToPx(4f), 0, dpToPx(4f), 0)
        }

        // Back button
        val backButton = VoidIconButton(context, R.drawable.ic_browser_back, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Back"
            layoutParams = buttonParams
            setOnClickListener { onBackClicked() }
        }

        // Forward button
        val forwardButton = VoidIconButton(context, R.drawable.ic_browser_forward, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Forward"
            layoutParams = buttonParams
            setOnClickListener { onForwardClicked() }
        }

        // Reload button
        val reloadButton = VoidIconButton(context, R.drawable.ic_browser_refresh, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Reload"
            layoutParams = buttonParams
            setOnClickListener { onReloadClicked() }
        }

        // Home button
        val homeButton = VoidIconButton(context, R.drawable.ic_browser_home, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Home"
            layoutParams = buttonParams
            setOnClickListener { onHomeClicked() }
        }

        // Address bar
        addressBar = EditText(context).apply {
            id = View.generateViewId()
            layoutParams = LayoutParams(0, dpToPx(44f), 1f).apply {
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
                        onUrlSubmitted(url)
                    }
                    onHideKeyboardRequested()
                }
                override val inputType: Int = this@apply.inputType
                override val imeOptions: Int = this@apply.imeOptions
            }

            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onShowKeyboardRequested(keyboardBinding)
                } else {
                    onHideKeyboardRequested()
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
            setOnClickListener { onBookmarkToggleClicked() }
        }

        // Geometry toggle (flat quad / curved cylinder)
        geometryButton = VoidIconButton(context, R.drawable.ic_browser_geometry, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Geometry"
            layoutParams = buttonParams
            setOnClickListener { onGeometryToggleClicked() }
        }

        // Close browser button
        val closeButton = VoidIconButton(context, R.drawable.ic_browser_close, VoidButtonStyle.SECONDARY).apply {
            contentDescription = "Close"
            layoutParams = buttonParams
            setOnClickListener { onCloseClicked() }
        }

        addView(backButton)
        addView(forwardButton)
        addView(reloadButton)
        addView(homeButton)
        addView(addressBar)
        addView(bookmarkButton)
        addView(geometryButton)
        addView(closeButton)
    }

    fun setUrl(url: String) {
        if (addressBar.text.toString() != url) {
            addressBar.setText(url)
        }
    }

    fun setIsBookmarked(isBookmarked: Boolean) {
        bookmarkButton.alpha = if (isBookmarked) 1.0f else 0.5f
    }

    fun setIsCurvedGeometry(isCurved: Boolean) {
        geometryButton.alpha = if (isCurved) 1.0f else 0.6f
    }
}
