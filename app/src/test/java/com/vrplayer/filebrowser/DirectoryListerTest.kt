package com.vrplayer.filebrowser

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// T5.1/T5.2: DirectoryLister.listMedia — listagem de um nivel + filtro por
// extensao de midia (video/audio/imagem). DirectoryLister so usa
// java.io.File + kotlinx.coroutines.Dispatchers.IO, entao roda como teste de
// JVM puro contra um diretorio temporario real, sem precisar de
// instrumentacao/emulador Android.
class DirectoryListerTest {

    private fun tempDir(): File = Files.createTempDirectory("lister-test").toFile().apply { deleteOnExit() }

    private fun file(dir: File, name: String, content: String = "x"): File =
        File(dir, name).apply { writeText(content) }

    @Test
    fun listsOnlyRecognizedMediaExtensionsAndDirectories() = runTest {
        val root = tempDir()
        file(root, "movie.mp4")
        file(root, "song.mp3")
        file(root, "photo.jpg")
        file(root, "notes.txt") // unsupported extension, should be filtered out
        file(root, "README") // no extension at all
        File(root, "subfolder").mkdir()

        val entries = DirectoryLister.listMedia(root)

        assertEquals(setOf("movie.mp4", "song.mp3", "photo.jpg", "subfolder"), entries.map { it.name }.toSet())
    }

    @Test
    fun classifiesEachEntryWithTheRightMediaType() = runTest {
        val root = tempDir()
        file(root, "movie.mkv")
        file(root, "song.flac")
        file(root, "photo.png")
        File(root, "subfolder").mkdir()

        val entries = DirectoryLister.listMedia(root).associateBy { it.name }

        assertEquals(MediaType.VIDEO, entries.getValue("movie.mkv").type)
        assertEquals(MediaType.AUDIO, entries.getValue("song.flac").type)
        assertEquals(MediaType.IMAGE, entries.getValue("photo.png").type)
        assertEquals(MediaType.DIRECTORY, entries.getValue("subfolder").type)
    }

    @Test
    fun extensionMatchingIsCaseInsensitive() = runTest {
        val root = tempDir()
        file(root, "MOVIE.MP4")

        val entries = DirectoryLister.listMedia(root)

        assertEquals(1, entries.size)
        assertEquals(MediaType.VIDEO, entries.first().type)
    }

    @Test
    fun directoriesAreListedRegardlessOfExtensionInTheirName() = runTest {
        val root = tempDir()
        // A directory literally named like a video file must still be classified as
        // MediaType.DIRECTORY, not VIDEO -- isDirectory() is checked before extension.
        File(root, "not_actually_a_video.mp4").mkdir()

        val entries = DirectoryLister.listMedia(root)

        assertEquals(1, entries.size)
        assertEquals(MediaType.DIRECTORY, entries.first().type)
    }

    @Test
    fun onlyListsOneLevelDeepNotRecursively() = runTest {
        val root = tempDir()
        val sub = File(root, "subfolder").apply { mkdir() }
        file(sub, "nested.mp4")

        val entries = DirectoryLister.listMedia(root)

        assertEquals(listOf("subfolder"), entries.map { it.name })
    }

    @Test
    fun emptyDirectoryReturnsEmptyList() = runTest {
        val root = tempDir()

        val entries = DirectoryLister.listMedia(root)

        assertTrue(entries.isEmpty())
    }

    @Test
    fun reportsFileSizeAndDirectoriesAsZeroSize() = runTest {
        val root = tempDir()
        file(root, "movie.mp4", content = "0123456789") // 10 bytes
        File(root, "subfolder").mkdir()

        val entries = DirectoryLister.listMedia(root).associateBy { it.name }

        assertEquals(10L, entries.getValue("movie.mp4").sizeBytes)
        assertEquals(0L, entries.getValue("subfolder").sizeBytes)
    }

    @Test
    fun mediaTypeForExtensionRecognizesAllDeclaredExtensionSets() {
        VIDEO_EXTENSIONS.forEach { assertEquals(MediaType.VIDEO, mediaTypeForExtension(it)) }
        AUDIO_EXTENSIONS.forEach { assertEquals(MediaType.AUDIO, mediaTypeForExtension(it)) }
        IMAGE_EXTENSIONS.forEach { assertEquals(MediaType.IMAGE, mediaTypeForExtension(it)) }
    }

    @Test
    fun mediaTypeForExtensionReturnsNullForUnknownExtensions() {
        assertNull(mediaTypeForExtension("txt"))
        assertNull(mediaTypeForExtension("exe"))
        assertNull(mediaTypeForExtension(""))
    }

    @Test
    fun mediaTypeForExtensionIsCaseInsensitive() {
        assertEquals(MediaType.VIDEO, mediaTypeForExtension("MP4"))
        assertEquals(MediaType.AUDIO, mediaTypeForExtension("Mp3"))
    }
}
