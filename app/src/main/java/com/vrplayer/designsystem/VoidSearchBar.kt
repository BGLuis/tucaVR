package com.vrplayer.designsystem

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.vrplayer.R
import com.vrplayer.VRActivity
import com.vrplayer.screens.ScreenHost
import com.vrplayer.screens.UiHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Barra de busca estilizada do design system Void com debounce de 500ms (T12.1),
 * botão de limpar texto e botão de colar.
 */
class VoidSearchBar(
    context: Context,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    hintText: String = "",
    private val activity: VRActivity? = null,
    private val onQueryChanged: (query: String) -> Unit
) : LinearLayout(context) {

    val editText: EditText
    private val clearBtn: VoidIconButton
    private var debounceJob: Job? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        editText = UiHelpers.buildVoidEditText(context, hintText, host).apply {
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginEnd = VoidTheme.dpToPx(context, 8f)
            }
            textSize = 16f
            val padH = VoidTheme.dpToPx(context, 14f)
            val padV = VoidTheme.dpToPx(context, 12f)
            setPadding(padH, padV, padH, padV)
            minimumHeight = VoidTheme.dpToPx(context, 54f)
        }
        addView(editText)

        clearBtn = VoidIconButton(context, R.drawable.icon_x, VoidButtonStyle.SECONDARY, isCircular = true).apply {
            layoutParams = LayoutParams(VoidTheme.dpToPx(context, 48f), VoidTheme.dpToPx(context, 48f)).also {
                it.marginEnd = VoidTheme.dpToPx(context, 8f)
            }
            visibility = View.GONE
            setOnClickListener {
                editText.setText("")
            }
        }
        addView(clearBtn)

        if (activity != null) {
            val pasteBtn = UiHelpers.buildPasteButton(context, activity, editText).apply {
                textSize = 15f
                minHeight = VoidTheme.dpToPx(context, 48f)
                val padH = VoidTheme.dpToPx(context, 14f)
                val padV = VoidTheme.dpToPx(context, 8f)
                setPadding(padH, padV, padH, padV)
            }
            addView(pasteBtn)
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                clearBtn.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE

                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(500L) // Debounce de 500ms (aviso T12.1 / T12.2)
                    onQueryChanged(query)
                }
            }
        })
    }

    fun getQuery(): String = editText.text.toString()

    fun setQuery(query: String) {
        editText.setText(query)
        editText.setSelection(query.length)
    }

    fun clear() {
        editText.setText("")
    }
}
