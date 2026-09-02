package com.tucavr.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PlaylistQueueManagerTest {

    private val samplePlaylist = Playlist(
        id = "pl_1",
        name = "Sci-Fi Hits",
        createdAt = 1000L,
        itemCount = 3
    )

    private val sampleItems = listOf(
        PlaylistItem("item_1", "pl_1", "/storage/video1.mp4", "Video 1", 60_000L, 0, "LOCAL"),
        PlaylistItem("item_2", "pl_1", "/storage/video2.mp4", "Video 2", 120_000L, 1, "LOCAL"),
        PlaylistItem("item_3", "pl_1", "/storage/video3.mp4", "Video 3", 180_000L, 2, "LOCAL")
    )

    @Test
    fun `test normal playback advances sequentially until end`() {
        val manager = PlaylistQueueManager()
        var playedItem: PlaylistItem? = null
        manager.onPlayItemRequested = { playedItem = it }

        manager.startPlaylist(samplePlaylist, sampleItems, startIndex = 0)
        assertEquals("item_1", manager.getCurrentItem()?.id)
        assertEquals("item_1", playedItem?.id)
        assertTrue(manager.hasNext())
        assertFalse(manager.hasPrevious())

        val second = manager.playNext()
        assertEquals("item_2", second?.id)
        assertEquals("item_2", playedItem?.id)
        assertTrue(manager.hasNext())
        assertTrue(manager.hasPrevious())

        val third = manager.playNext()
        assertEquals("item_3", third?.id)
        assertEquals("item_3", playedItem?.id)
        assertFalse(manager.hasNext())
        assertTrue(manager.hasPrevious())

        val ended = manager.playNext()
        assertNull(ended)
        assertEquals("item_3", manager.getCurrentItem()?.id)
    }

    @Test
    fun `test repeat all wraps around to beginning`() {
        val manager = PlaylistQueueManager()
        var playedItem: PlaylistItem? = null
        manager.onPlayItemRequested = { playedItem = it }

        manager.setPlaybackMode(PlaybackMode.REPEAT_ALL)
        manager.startPlaylist(samplePlaylist, sampleItems, startIndex = 2)

        assertEquals("item_3", manager.getCurrentItem()?.id)
        assertTrue(manager.hasNext())

        val wrapped = manager.playNext()
        assertNotNull(wrapped)
        assertEquals("item_1", wrapped?.id)
        assertEquals("item_1", playedItem?.id)

        val previousWrapped = manager.playPrevious()
        assertEquals("item_3", previousWrapped?.id)
    }

    @Test
    fun `test repeat one repeats the current item continuously`() {
        val manager = PlaylistQueueManager()
        var playCount = 0
        manager.onPlayItemRequested = { playCount++ }

        manager.startPlaylist(samplePlaylist, sampleItems, startIndex = 1)
        manager.setPlaybackMode(PlaybackMode.REPEAT_ONE)
        assertEquals("item_2", manager.getCurrentItem()?.id)
        assertEquals(1, playCount)

        val next = manager.playNext()
        assertEquals("item_2", next?.id)
        assertEquals(2, playCount)

        val prev = manager.playPrevious()
        assertEquals("item_2", prev?.id)
        assertEquals(3, playCount)
    }

    @Test
    fun `test shuffle mode preserves all items without missing any`() {
        val manager = PlaylistQueueManager(random = Random(42))
        manager.setPlaybackMode(PlaybackMode.SHUFFLE)
        manager.startPlaylist(samplePlaylist, sampleItems, startIndex = 0)

        assertEquals("item_1", manager.getCurrentItem()?.id)

        val playedIds = mutableListOf<String>()
        playedIds.add(manager.getCurrentItem()!!.id)

        while (manager.hasNext()) {
            val next = manager.playNext() ?: break
            playedIds.add(next.id)
            if (playedIds.size >= sampleItems.size) break
        }

        assertEquals(3, playedIds.size)
        assertTrue(playedIds.contains("item_1"))
        assertTrue(playedIds.contains("item_2"))
        assertTrue(playedIds.contains("item_3"))
    }

    @Test
    fun `test skip to specific index`() {
        val manager = PlaylistQueueManager()
        var lastPlayed: PlaylistItem? = null
        manager.onPlayItemRequested = { lastPlayed = it }

        manager.startPlaylist(samplePlaylist, sampleItems, startIndex = 0)
        assertEquals("item_1", manager.getCurrentItem()?.id)

        val skipped = manager.skipTo(2)
        assertEquals("item_3", skipped?.id)
        assertEquals("item_3", lastPlayed?.id)
        assertEquals(2, manager.getCurrentIndex())
    }

    @Test
    fun `test auto advance on playback progress reaching end`() {
        val manager = PlaylistQueueManager()
        var playedItem: PlaylistItem? = null
        manager.onPlayItemRequested = { playedItem = it }

        manager.startPlaylist(samplePlaylist, sampleItems, startIndex = 0)
        assertEquals("item_1", manager.getCurrentItem()?.id)

        // Progresso no meio da mídia não deve avançar
        manager.onPlaybackProgress(currentSec = 30f, totalSec = 60f)
        assertEquals("item_1", manager.getCurrentItem()?.id)

        // Ao se aproximar do final (faltando menos de 0.8s), avança automaticamente
        manager.onPlaybackProgress(currentSec = 59.5f, totalSec = 60f)
        assertEquals("item_2", manager.getCurrentItem()?.id)
        assertEquals("item_2", playedItem?.id)
    }

    @Test
    fun `test reorder swap items positions`() {
        val itemA = sampleItems[0] // pos 0
        val itemB = sampleItems[1] // pos 1

        val updatedA = itemA.copy(position = itemB.position)
        val updatedB = itemB.copy(position = itemA.position)

        assertEquals(1, updatedA.position)
        assertEquals(0, updatedB.position)

        val reorderedList = listOf(updatedB, updatedA, sampleItems[2]).sortedBy { it.position }
        assertEquals("item_2", reorderedList[0].id)
        assertEquals("item_1", reorderedList[1].id)
        assertEquals("item_3", reorderedList[2].id)
    }

    @Test
    fun `test remove item and reorder remaining indices`() {
        val items = sampleItems.toMutableList()
        // Remove item_2 (índice 1)
        items.removeAt(1)

        val reordered = items.mapIndexed { index, item ->
            item.copy(position = index)
        }

        assertEquals(2, reordered.size)
        assertEquals("item_1", reordered[0].id)
        assertEquals(0, reordered[0].position)
        assertEquals("item_3", reordered[1].id)
        assertEquals(1, reordered[1].position)
    }
}
