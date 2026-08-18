package com.vrplayer.screens

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Funções de formatação de dados compartilhadas entre screens.
 *
 * Antes estavam como funções privadas top-level em VRPresentation.kt;
 * centralizadas aqui para evitar duplicação e facilitar testes.
 */

fun formatFileSize(context: Context, bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        context.getString(com.vrplayer.R.string.browser_label_size_gb_format, mb / 1024.0)
    } else {
        context.getString(com.vrplayer.R.string.browser_label_size_mb_format, mb)
    }
}

fun formatModifiedDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
