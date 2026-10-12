package com.tucavr.browser.ui

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.tucavr.designsystem.KeyboardBinding
import java.util.concurrent.atomic.AtomicBoolean

// Presentation em VirtualDisplay não recebe IME do sistema: o teclado só abre pelo proxy da VRActivity.
@Composable
fun NativeKeyboardEffect(
    interactionSource: InteractionSource,
    text: String,
    onTextChange: (String) -> Unit,
    keyboardInputType: Int,
    keyboardImeOptions: Int,
    onImeAction: (Int) -> Unit,
    onShowNativeKeyboard: ((KeyboardBinding) -> Unit)?,
    onHideNativeKeyboard: (() -> Unit)?,
) {
    val latestText by rememberUpdatedState(text)
    val latestOnTextChange by rememberUpdatedState(onTextChange)
    val latestOnImeAction by rememberUpdatedState(onImeAction)
    val latestOnShow by rememberUpdatedState(onShowNativeKeyboard)
    val latestOnHide by rememberUpdatedState(onHideNativeKeyboard)
    val isShowing = remember { AtomicBoolean(false) }

    val binding =
        remember(keyboardInputType, keyboardImeOptions) {
            object : KeyboardBinding {
                override val inputType: Int = keyboardInputType
                override val imeOptions: Int = keyboardImeOptions

                override fun currentText(): CharSequence = latestText

                override fun onKeyboardText(
                    text: CharSequence,
                    selection: Int,
                ) = latestOnTextChange(text.toString())

                override fun onImeAction(actionId: Int) = latestOnImeAction(actionId)
            }
        }

    // O foco do Compose não é confiável numa janela sem foco; o toque no campo também abre o teclado.
    LaunchedEffect(interactionSource, binding) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is FocusInteraction.Focus, is PressInteraction.Release -> {
                    isShowing.set(true)
                    latestOnShow?.invoke(binding)
                }
                is FocusInteraction.Unfocus -> {
                    isShowing.set(false)
                    latestOnHide?.invoke()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { if (isShowing.getAndSet(false)) latestOnHide?.invoke() }
    }
}
