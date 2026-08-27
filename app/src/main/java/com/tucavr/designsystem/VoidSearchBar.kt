package com.tucavr.designsystem

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.tucavr.VRActivity
import com.tucavr.screens.ScreenHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Barra de busca estilizada do design system Void com debounce de 500ms (T12.1),
 * composta internamente sobre [VoidTextField].
 */
class VoidSearchBar(
    context: Context,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    hintText: String = "",
    private val activity: VRActivity? = null,
    private val onQueryChanged: (query: String) -> Unit
) : LinearLayout(context) {

    val textField: VoidTextField
    val editText: EditText get() = textField.editText
    private var debounceJob: Job? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        textField = VoidTextField(
            context = context,
            host = host,
            hint = hintText,
            kind = VoidFieldKind.TEXT,
            actions = setOf(VoidFieldAction.CLEAR, VoidFieldAction.PASTE, VoidFieldAction.CONTEXT_MENU)
        ).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(textField)

        textField.setOnTextChangedListener { query ->
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(500L) // Debounce de 500ms (T12.1 / T12.2)
                onQueryChanged(query)
            }
        }
    }

    fun getQuery(): String = textField.getText()

    fun setQuery(query: String) {
        textField.setText(query)
    }

    fun clear() {
        textField.clear()
    }
}
