package com.tucavr

import java.io.File

internal object TestPaths {
    val moduleDir: File by lazy {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        listOf(userDir, File(userDir, "app"))
            .firstOrNull { File(it, "src/main/java").isDirectory }
            ?: error("Módulo app não encontrado a partir de: ${userDir.absolutePath}")
    }

    val mainSources: File get() = File(moduleDir, "src/main/java")
}
