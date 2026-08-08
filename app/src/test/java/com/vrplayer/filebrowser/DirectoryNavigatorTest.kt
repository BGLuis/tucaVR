package com.vrplayer.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// T5.5: navegacao hierarquica (enter/goBack) via back-stack simples.
class DirectoryNavigatorTest {

    private fun tempDir(): File = Files.createTempDirectory("navigator-test").toFile().apply { deleteOnExit() }

    @Test
    fun startsAtTheGivenRoot() {
        val root = tempDir()

        val navigator = DirectoryNavigator(root)

        assertEquals(root, navigator.currentPath)
        assertFalse(navigator.canGoBack())
    }

    @Test
    fun enterPushesCurrentDirectoryOntoTheBackStack() {
        val root = tempDir()
        val child = File(root, "child").apply { mkdir() }
        val navigator = DirectoryNavigator(root)

        navigator.enter(child)

        assertEquals(child, navigator.currentPath)
        assertTrue(navigator.canGoBack())
    }

    @Test
    fun goBackReturnsToThePreviousDirectory() {
        val root = tempDir()
        val child = File(root, "child").apply { mkdir() }
        val navigator = DirectoryNavigator(root)
        navigator.enter(child)

        val didGoBack = navigator.goBack()

        assertTrue(didGoBack)
        assertEquals(root, navigator.currentPath)
        assertFalse(navigator.canGoBack())
    }

    @Test
    fun goBackAtTheRootReturnsFalseAndStaysPut() {
        val root = tempDir()
        val navigator = DirectoryNavigator(root)

        val didGoBack = navigator.goBack()

        assertFalse(didGoBack)
        assertEquals(root, navigator.currentPath)
    }

    @Test
    fun multipleEntersBuildADeepBackStackInOrder() {
        val root = tempDir()
        val a = File(root, "a").apply { mkdir() }
        val b = File(a, "b").apply { mkdir() }
        val c = File(b, "c").apply { mkdir() }
        val navigator = DirectoryNavigator(root)

        navigator.enter(a)
        navigator.enter(b)
        navigator.enter(c)

        assertEquals(c, navigator.currentPath)
        assertTrue(navigator.goBack())
        assertEquals(b, navigator.currentPath)
        assertTrue(navigator.goBack())
        assertEquals(a, navigator.currentPath)
        assertTrue(navigator.goBack())
        assertEquals(root, navigator.currentPath)
        assertFalse(navigator.goBack())
    }

    @Test(expected = IllegalArgumentException::class)
    fun enteringAFileInsteadOfADirectoryThrows() {
        val root = tempDir()
        val file = File(root, "not_a_dir.txt").apply { createNewFile() }
        val navigator = DirectoryNavigator(root)

        navigator.enter(file)
    }
}
