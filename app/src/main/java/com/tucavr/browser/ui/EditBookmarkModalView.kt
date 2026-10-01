package com.tucavr.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tucavr.browser.data.BrowserBookmark
import com.tucavr.designsystem.KeyboardBinding
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme

/**
 * Modal overlay for creating and editing VR browser bookmarks.
 */
class EditBookmarkModalView(
    context: Context,
    private val onSaveRequested: (editingBookmark: BrowserBookmark?, title: String, url: String) -> Unit,
    private val onDismissRequested: () -> Unit,
    private val onShowKeyboardRequested: (KeyboardBinding) -> Unit,
    private val onHideKeyboardRequested: () -> Unit
) : FrameLayout(context) {

    private lateinit var modalTitleView: TextView
    private lateinit var modalTitleInput: EditText
    private lateinit var modalUrlInput: EditText
    private var editingBookmark: BrowserBookmark? = null

    private fun dpToPx(dp: Float): Int = VoidTheme.dpToPx(context, dp)

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.parseColor("#CC000000"))
        visibility = GONE
        isClickable = true
        isFocusable = true

        buildUi()
    }

    private fun buildUi() {
        val dialogBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(24f), dpToPx(24f), dpToPx(24f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = VoidTheme.dp(context, 16f)
                setStroke(VoidTheme.dpToPx(context, 2f), VoidTheme.colorAccent)
            }
            layoutParams = LayoutParams(dpToPx(480f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
                    onShowKeyboardRequested(titleBinding)
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
                    submitSave()
                }
                override val inputType: Int = this@apply.inputType
                override val imeOptions: Int = this@apply.imeOptions
            }

            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onShowKeyboardRequested(urlBinding)
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
            setOnClickListener { dismiss() }
        }

        val saveButton = VoidButton(context, VoidButtonStyle.PRIMARY, isCompact = true).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpToPx(12f) }
            setOnClickListener { submitSave() }
        }

        actionsLayout.addView(cancelButton)
        actionsLayout.addView(saveButton)
        dialogBox.addView(actionsLayout)

        addView(dialogBox)
    }

    private fun submitSave() {
        val title = modalTitleInput.text.toString().trim()
        val url = modalUrlInput.text.toString().trim()
        onSaveRequested(editingBookmark, title, url)
        dismiss()
    }

    fun show(bookmarkToEdit: BrowserBookmark?) {
        editingBookmark = bookmarkToEdit
        if (bookmarkToEdit != null) {
            modalTitleView.text = "Edit Bookmark"
            modalTitleInput.setText(bookmarkToEdit.title)
            modalUrlInput.setText(bookmarkToEdit.url)
        } else {
            modalTitleView.text = "New Bookmark"
            modalTitleInput.setText("")
            modalUrlInput.setText("https://")
        }
        visibility = VISIBLE
        modalTitleInput.requestFocus()
    }

    fun dismiss() {
        onHideKeyboardRequested()
        visibility = GONE
        editingBookmark = null
    }
}
