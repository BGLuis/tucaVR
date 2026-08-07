package com.vrplayer.filebrowser

import java.io.File

class DirectoryNavigator(rootDir: File) {

    private val backStack = ArrayDeque<File>()

    var currentPath: File = rootDir
        private set

    fun enter(dir: File) {
        require(dir.isDirectory) { "Not a directory: ${dir.absolutePath}" }
        backStack.addLast(currentPath)
        currentPath = dir
    }

    fun goBack(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        currentPath = previous
        return true
    }

    fun canGoBack(): Boolean = backStack.isNotEmpty()
}
