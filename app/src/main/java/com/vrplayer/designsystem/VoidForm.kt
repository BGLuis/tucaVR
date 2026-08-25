package com.vrplayer.designsystem

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vrplayer.screens.ScreenHost

/**
 * Contêiner de formulário que gerencia a ordem dos [VoidTextField], navegação IME (Next/Done),
 * validação integrada e rolagem focada.
 */
class VoidForm(context: Context) : LinearLayout(context) {

    private val fieldList = mutableListOf<VoidTextField>()
    var onFormSubmit: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Cria e adiciona um [VoidTextField] ao formulário, registrando-o na ordem de navegação.
     */
    fun field(
        host: ScreenHost,
        label: String? = null,
        hint: String = "",
        kind: VoidFieldKind = VoidFieldKind.TEXT,
        actions: Set<VoidFieldAction> = setOf(VoidFieldAction.PASTE, VoidFieldAction.CONTEXT_MENU),
        validator: ((String) -> String?)? = null
    ): VoidTextField {
        val f = VoidTextField(
            context = context,
            host = host,
            label = label,
            hint = hint,
            kind = kind,
            actions = actions,
            validator = validator
        )
        addField(f)
        return f
    }

    /**
     * Adiciona um [VoidTextField] já instanciado ao formulário.
     */
    fun addField(field: VoidTextField) {
        fieldList.add(field)
        if (field.parent == null) {
            addView(field)
        }
        rebindImeActions()
    }

    /**
     * Reencadeia as ações IME_ACTION_NEXT e IME_ACTION_DONE dinamicamente.
     */
    fun rebindImeActions() {
        for (i in fieldList.indices) {
            val currentField = fieldList[i]
            currentField.onImeNext = {
                val next = findNextVisibleField(i)
                if (next != null) {
                    next.editText.requestFocus()
                } else {
                    onFormSubmit?.invoke()
                }
            }
            currentField.onImeDone = {
                onFormSubmit?.invoke()
            }
        }
    }

    /**
     * Encontra o próximo campo visível na lista a partir do índice dado.
     */
    fun findNextVisibleField(currentIndex: Int): VoidTextField? {
        return findNextVisibleItem(fieldList, currentIndex) { field ->
            field.visibility == View.VISIBLE && field.isParentHierarchyVisible()
        }
    }

    /**
     * Executa a validação de todos os campos visíveis.
     * Retorna `true` se todos forem válidos, ou `false` se houver algum erro.
     */
    fun validate(): Boolean {
        var allValid = true
        var firstInvalid: VoidTextField? = null
        for (f in fieldList) {
            if (f.visibility == View.VISIBLE && f.isParentHierarchyVisible()) {
                val valid = f.validate()
                if (!valid) {
                    allValid = false
                    if (firstInvalid == null) {
                        firstInvalid = f
                    }
                }
            }
        }
        firstInvalid?.editText?.requestFocus()
        return allValid
    }

    /**
     * Foca o primeiro campo inválido, se houver.
     */
    fun focusFirstInvalid() {
        for (f in fieldList) {
            if (f.visibility == View.VISIBLE && f.isParentHierarchyVisible() && !f.validate()) {
                f.editText.requestFocus()
                return
            }
        }
    }

    /**
     * Limpa o texto e erros de todos os campos.
     */
    fun clearAll() {
        for (f in fieldList) {
            f.clear()
            f.clearError()
        }
    }

    /**
     * Retorna a lista de campos registrados.
     */
    fun getFields(): List<VoidTextField> = fieldList

    private fun View.isParentHierarchyVisible(): Boolean {
        var p = (this as View).parent
        while (p is View) {
            val v = p as View
            if (v.visibility != View.VISIBLE) return false
            p = v.parent
        }
        return true
    }

    companion object {
        /**
         * Função pura para resolução do próximo item visível (testável em JVM sem Android Views).
         */
        fun <T> findNextVisibleItem(items: List<T>, currentIndex: Int, isVisible: (T) -> Boolean): T? {
            if (currentIndex < 0 || currentIndex >= items.size) return null
            for (i in (currentIndex + 1) until items.size) {
                if (isVisible(items[i])) {
                    return items[i]
                }
            }
            return null
        }
    }
}
