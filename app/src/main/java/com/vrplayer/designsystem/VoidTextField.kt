package com.vrplayer.designsystem

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.text.method.SingleLineTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.vrplayer.R
import com.vrplayer.screens.ScreenHost

/**
 * Tipos semânticos de campo suportados pelo [VoidTextField].
 */
enum class VoidFieldKind {
    TEXT,
    NUMBER,
    PASSWORD,
    URI,
    MULTILINE;

    /**
     * Mapeia o tipo de campo para o valor correspondente do `InputType` do Android.
     */
    fun toAndroidInputType(isPasswordRevealed: Boolean = false): Int {
        return when (this) {
            TEXT -> InputType.TYPE_CLASS_TEXT
            NUMBER -> InputType.TYPE_CLASS_NUMBER
            PASSWORD -> {
                if (isPasswordRevealed) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            URI -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            MULTILINE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
    }
}

/**
 * Ações exibidas na área de sufixo (trailing) do [VoidTextField].
 */
enum class VoidFieldAction {
    PASTE,
    CLEAR,
    REVEAL,
    CONTEXT_MENU
}

/**
 * Componente unificado de entrada de texto para o painel VR.
 *
 * Garante:
 * - Conformidade com o design system Void (18sp, altura mínima 76px, cantos arredondados, bordas).
 * - Integração automática e obrigatória com o teclado nativo via [ScreenHost] e [KeyboardBinding].
 * - Espelhamento bidirecional seguro entre o campo e o proxy da Activity.
 * - Linha de erro com altura reservada fixa para evitar saltos (jitter) no layout em VR.
 * - Rolagem automática da área visível com [requestRectangleOnScreen] ao focar.
 */
class VoidTextField(
    context: Context,
    private val host: ScreenHost,
    label: String? = null,
    hint: String = "",
    val kind: VoidFieldKind = VoidFieldKind.TEXT,
    private val actions: Set<VoidFieldAction> = setOf(VoidFieldAction.PASTE, VoidFieldAction.CONTEXT_MENU),
    private val validator: ((String) -> String?)? = null,
    var onImeNext: (() -> Unit)? = null,
    var onImeDone: (() -> Unit)? = null
) : LinearLayout(context), KeyboardBinding {

    val editText: EditText
    private val inputContainer: LinearLayout
    private val errorView: TextView
    private var currentError: String? = null

    var isPasswordRevealed: Boolean = false
        private set

    private var btnClear: VoidIconButton? = null
    private var btnReveal: VoidIconButton? = null
    private var onTextChangedCallback: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // 1. Rótulo superior (se fornecido)
        if (label != null) {
            val labelView = VoidText.mono(context, label, sizeSp = 13f, secondary = true).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            }
            addView(labelView)
        }

        // 2. Linha do campo de texto com ações embutidas
        inputContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = VoidTheme.dpToPx(context, 76f)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        editText = EditText(context).apply {
            this.hint = hint
            setHintTextColor(VoidTheme.colorTextSecondary)
            setTextColor(VoidTheme.colorText)
            textSize = 18f
            typeface = if (kind == VoidFieldKind.NUMBER || kind == VoidFieldKind.PASSWORD) {
                VoidTheme.typefaceMono
            } else {
                VoidTheme.typefaceBody
            }
            background = null

            val padH = VoidTheme.dpToPx(context, 16f)
            val padV = VoidTheme.dpToPx(context, 16f)
            setPadding(padH, padV, padH, padV)

            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            // Configuração do InputType e comportamento de linha
            inputType = kind.toAndroidInputType(isPasswordRevealed)
            when (kind) {
                VoidFieldKind.PASSWORD -> {
                    transformationMethod = PasswordTransformationMethod.getInstance()
                    setSingleLine(true)
                }
                VoidFieldKind.MULTILINE -> {
                    setSingleLine(false)
                    minLines = 3
                }
                else -> {
                    setSingleLine(true)
                }
            }

            setOnFocusChangeListener { _, hasFocus ->
                updateContainerBackground(hasFocus)
                if (hasFocus) {
                    host.showNativeKeyboard(this@VoidTextField)
                    val rect = Rect(0, 0, width, height)
                    requestRectangleOnScreen(rect, true)
                } else {
                    host.hideNativeKeyboard()
                    validate()
                }
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString() ?: ""
                    btnClear?.visibility = if (text.isNotEmpty() && actions.contains(VoidFieldAction.CLEAR)) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    if (currentError != null) {
                        clearError()
                    }
                    onTextChangedCallback?.invoke(text)
                }
            })
        }
        inputContainer.addView(editText)

        // Botões de ação adicionais
        val btnSize = VoidTheme.dpToPx(context, 54f)
        val btnMargin = LayoutParams(btnSize, btnSize).apply {
            marginEnd = VoidTheme.dpToPx(context, 6f)
        }

        // Ação: Revelar/Ocultar Senha
        if (actions.contains(VoidFieldAction.REVEAL) && kind == VoidFieldKind.PASSWORD) {
            btnReveal = VoidIconButton(
                context,
                R.drawable.icon_eye_off,
                VoidButtonStyle.SECONDARY,
                isCircular = true
            ).apply {
                layoutParams = btnMargin
                setOnClickListener {
                    togglePasswordReveal()
                }
            }
            inputContainer.addView(btnReveal)
        }

        // Ação: Limpar texto
        if (actions.contains(VoidFieldAction.CLEAR)) {
            btnClear = VoidIconButton(
                context,
                R.drawable.icon_x,
                VoidButtonStyle.SECONDARY,
                isCircular = true
            ).apply {
                layoutParams = btnMargin
                visibility = if (getText().isNotEmpty()) View.VISIBLE else View.GONE
                setOnClickListener {
                    clear()
                    editText.requestFocus()
                }
            }
            inputContainer.addView(btnClear)
        }

        // Ação: Colar
        if (actions.contains(VoidFieldAction.PASTE)) {
            val btnPaste = VoidIconButton(
                context,
                R.drawable.ic_content_paste,
                VoidButtonStyle.SECONDARY,
                isCircular = true
            ).apply {
                layoutParams = btnMargin
                setOnClickListener {
                    pasteFromClipboard()
                    editText.requestFocus()
                }
            }
            inputContainer.addView(btnPaste)
        }

        // Ação: Menu de Contexto
        if (actions.contains(VoidFieldAction.CONTEXT_MENU)) {
            val btnMenu = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = "⋮"
                textSize = 18f
                val pad = VoidTheme.dpToPx(context, 4f)
                setPadding(pad, pad, pad, pad)
                minHeight = btnSize
                layoutParams = btnMargin
                setOnClickListener {
                    openContextMenu()
                }
            }
            inputContainer.addView(btnMenu)
        }

        updateContainerBackground(false)
        addView(inputContainer)

        // 3. Linha de erro com altura reservada fixa (impede saltos de layout no painel VR)
        errorView = TextView(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, VoidTheme.dpToPx(context, 20f)).apply {
                topMargin = VoidTheme.dpToPx(context, 2f)
            }
            textSize = 12f
            typeface = VoidTheme.typefaceBody
            setTextColor(Color.parseColor("#FF5555"))
            text = ""
            visibility = View.VISIBLE
        }
        addView(errorView)
    }

    // ---- Implementação da interface KeyboardBinding ----

    override val inputType: Int
        get() = kind.toAndroidInputType(isPasswordRevealed)

    override val imeOptions: Int
        get() = when {
            onImeNext != null -> EditorInfo.IME_ACTION_NEXT
            onImeDone != null -> EditorInfo.IME_ACTION_DONE
            else -> EditorInfo.IME_ACTION_UNSPECIFIED
        }

    override fun currentText(): CharSequence = editText.text ?: ""

    override fun onKeyboardText(text: CharSequence, selection: Int) {
        if (editText.text.toString() != text.toString()) {
            editText.setText(text)
            val sel = selection.coerceIn(0, editText.text.length)
            editText.setSelection(sel)
        }
    }

    override fun onImeAction(actionId: Int) {
        if (actionId == EditorInfo.IME_ACTION_NEXT) {
            onImeNext?.invoke()
        } else if (actionId == EditorInfo.IME_ACTION_DONE) {
            onImeDone?.invoke()
        }
    }

    // ---- Métodos de Manipulação de Texto e Estado ----

    fun getText(): String = editText.text.toString()

    fun setText(text: CharSequence) {
        if (editText.text.toString() != text.toString()) {
            editText.setText(text)
            editText.setSelection(editText.text.length)
            host.syncKeyboard(this)
        }
    }

    fun clear() {
        setText("")
    }

    fun setOnTextChangedListener(listener: (String) -> Unit) {
        onTextChangedCallback = listener
    }

    fun togglePasswordReveal() {
        if (kind != VoidFieldKind.PASSWORD) return
        isPasswordRevealed = !isPasswordRevealed
        editText.inputType = kind.toAndroidInputType(isPasswordRevealed)
        editText.transformationMethod = if (isPasswordRevealed) {
            SingleLineTransformationMethod.getInstance()
        } else {
            PasswordTransformationMethod.getInstance()
        }
        editText.setSelection(editText.text.length)
        host.syncKeyboard(this)
    }

    fun validate(): Boolean {
        val validatorFn = validator ?: return true
        val error = validatorFn(getText())
        setError(error)
        return error == null
    }

    fun setError(error: String?) {
        currentError = error
        errorView.text = error ?: ""
        updateContainerBackground(editText.hasFocus())
    }

    fun clearError() {
        setError(null)
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(context).toString()
            setText(text)
        }
    }

    fun copyToClipboard() {
        if (kind == VoidFieldKind.PASSWORD) return // Regra de segurança: nunca copia senhas
        val textToCopy = if (editText.hasSelection()) {
            editText.text.subSequence(editText.selectionStart, editText.selectionEnd).toString()
        } else {
            editText.text.toString()
        }
        if (textToCopy.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("text", textToCopy)
        clipboard?.setPrimaryClip(clip)
    }

    fun cutToClipboard() {
        if (kind == VoidFieldKind.PASSWORD) return
        copyToClipboard()
        clear()
    }

    fun selectAll() {
        editText.selectAll()
    }

    private fun openContextMenu() {
        val menu = VoidContextMenu(context, host, this)
        host.showOverlay(menu)
    }

    private fun updateContainerBackground(hasFocus: Boolean) {
        val strokeColor = when {
            currentError != null -> Color.parseColor("#FF5555")
            hasFocus -> VoidTheme.colorAccent
            else -> VoidTheme.colorBorder
        }
        val bg = GradientDrawable().apply {
            setColor(VoidTheme.colorSurface)
            cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
            setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), strokeColor)
        }
        inputContainer.background = bg
    }
}
