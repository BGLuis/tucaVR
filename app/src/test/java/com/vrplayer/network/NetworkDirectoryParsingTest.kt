package com.vrplayer.network

import com.vrplayer.filebrowser.Format3DType
import com.vrplayer.filebrowser.MediaEntry
import com.vrplayer.filebrowser.MediaFilterEngine
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.mediaTypeForExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDirectoryParsingTest {

    @Test
    fun parsesTabSeparatedOutputIntoMediaEntries() {
        val rawResponse = "3D Movies\t1\t0\nAvatar.3D-SBS.mkv\t0\t1048576000\nMusic\t1\t0\nsong.mp3\t0\t5242880\n"

        val lines = rawResponse.split("\n").filter { it.isNotBlank() }
        val entries = lines.mapNotNull { line ->
            val parts = line.split("\t")
            val name = parts.getOrNull(0) ?: return@mapNotNull null
            val isDir = parts.getOrNull(1) == "1"
            val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val type = if (isDir) MediaType.DIRECTORY else (mediaTypeForExtension(name.substringAfterLast('.', "")) ?: MediaType.VIDEO)
            val f3d = if (type == MediaType.VIDEO) MediaFilterEngine.detectFormat3DFromFilename(name) else Format3DType.FLAT_2D

            MediaEntry(
                name = name,
                path = name,
                sizeBytes = sizeBytes,
                lastModified = 0L,
                type = type,
                format3DHint = f3d
            )
        }

        assertEquals(4, entries.size)

        // Entrada 1: Diretório
        assertEquals("3D Movies", entries[0].name)
        assertEquals(MediaType.DIRECTORY, entries[0].type)

        // Entrada 2: Vídeo 3D SBS
        assertEquals("Avatar.3D-SBS.mkv", entries[1].name)
        assertEquals(MediaType.VIDEO, entries[1].type)
        assertEquals(Format3DType.SBS, entries[1].format3DHint)
        assertEquals(1048576000L, entries[1].sizeBytes)

        // Entrada 3: Diretório
        assertEquals("Music", entries[2].name)
        assertEquals(MediaType.DIRECTORY, entries[2].type)

        // Entrada 4: Áudio
        assertEquals("song.mp3", entries[3].name)
        assertEquals(MediaType.AUDIO, entries[3].type)
    }
}
