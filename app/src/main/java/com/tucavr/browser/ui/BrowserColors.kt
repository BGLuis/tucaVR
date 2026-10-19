package com.tucavr.browser.ui

import androidx.compose.ui.graphics.Color
import com.tucavr.designsystem.VoidTheme

internal object BrowserColors {
    val background = Color(VoidTheme.colorBackground)
    val surface = Color(VoidTheme.colorSurface)
    val surfaceAlt = Color(VoidTheme.colorSurfaceAlt)
    val text = Color(VoidTheme.colorText)
    val textSecondary = Color(VoidTheme.colorTextSecondary)
    val accent = Color(VoidTheme.colorAccent)
    val border = Color(VoidTheme.colorBorder)

    // O VoidTheme não tem token de erro; mesmo vermelho usado nos validadores do VoidTextField.
    val danger = Color(0xFFFF5555)
    val errorBanner = Color(0xFFB00020)
}
