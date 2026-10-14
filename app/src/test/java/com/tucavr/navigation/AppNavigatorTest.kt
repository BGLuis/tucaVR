package com.tucavr.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AppNavigator].
 * Guards back-stack integrity, single-player depth during playlist advance,
 * proper stack clearing on stopPlayback (Issues #82 and #83), and section
 * selection guarantees for the 3-zone curved spatial UI.
 */
class AppNavigatorTest {
    @Test
    fun initialStateStartsAtHomeWithEmptyStack() {
        val nav = AppNavigator()
        assertEquals(Destination.Home, nav.current)
        assertEquals(0, nav.stackDepth)
        assertEquals(listOf(Destination.Home), nav.stack)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun navigateToPushesPreviousDestinationAndUpdatesCurrent() {
        val nav = AppNavigator()
        val localFiles = Destination.LocalFiles("/sdcard/Movies")

        nav.navigateTo(localFiles)

        assertEquals(localFiles, nav.current)
        assertEquals(1, nav.stackDepth)
        assertEquals(listOf(Destination.Home, localFiles), nav.stack)
        assertTrue(nav.canGoBack)

        assertTrue(nav.back())
        assertEquals(Destination.Home, nav.current)
        assertEquals(0, nav.stackDepth)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun roundTripBetweenHomeAndNetworkLeavesStackEmptyAndCurrentAtHome() {
        val nav = AppNavigator()

        // Home -> NetworkHome
        nav.navigateTo(Destination.NetworkHome)
        assertEquals(Destination.NetworkHome, nav.current)
        assertEquals(1, nav.stackDepth)

        // Back from NetworkHome should pop back to Home (stack size 0)
        assertTrue(nav.back())
        assertEquals(Destination.Home, nav.current)
        assertEquals(0, nav.stackDepth)

        // Multiple round trips must not accumulate stack entries
        for (i in 1..5) {
            nav.navigateTo(Destination.NetworkHome)
            assertTrue(nav.back())
        }
        assertEquals(Destination.Home, nav.current)
        assertEquals(0, nav.stackDepth)
    }

    @Test
    fun replaceTopUpdatesCurrentWithoutGrowingStack() {
        val nav = AppNavigator()
        val folder = Destination.LocalFiles("/sdcard/Movies")
        nav.navigateTo(folder)
        assertEquals(1, nav.stackDepth)

        val player1 = Destination.Player(PlaybackSource.LocalFile("/sdcard/Movies/ep1.mp4", 1000L))
        nav.navigateTo(player1)
        assertEquals(2, nav.stackDepth)
        assertEquals(player1, nav.current)

        val player2 = Destination.Player(PlaybackSource.LocalFile("/sdcard/Movies/ep2.mp4", 1000L))
        nav.replaceTop(player2)
        assertEquals(2, nav.stackDepth)
        assertEquals(player2, nav.current)

        // Back from player2 should go directly to folder, not player1
        assertTrue(nav.back())
        assertEquals(folder, nav.current)
        assertEquals(1, nav.stackDepth)
    }

    @Test
    fun advancingPlaylistLeavesExactlyOnePlayerOnStack() {
        val nav = AppNavigator()
        val folder = Destination.LocalFiles("/sdcard/Series/Season1")
        nav.navigateTo(folder)

        // First item navigates to player
        val ep1 = Destination.Player(PlaybackSource.LocalFile("ep1.mp4", 100L))
        nav.navigateTo(ep1)
        assertEquals(2, nav.stackDepth)

        // Advancing playlist items uses replaceTop
        val ep2 = Destination.Player(PlaybackSource.LocalFile("ep2.mp4", 100L))
        nav.replaceTop(ep2)
        val ep3 = Destination.Player(PlaybackSource.LocalFile("ep3.mp4", 100L))
        nav.replaceTop(ep3)

        assertEquals(ep3, nav.current)
        assertEquals(2, nav.stackDepth)

        // Pop player should reveal folder directly
        assertTrue(nav.popTo { it !is Destination.Player })
        assertEquals(folder, nav.current)
        assertEquals(1, nav.stackDepth)
    }

    @Test
    fun popToRestoresOriginatingFolderOnStopPlayback() {
        val nav = AppNavigator()
        val folder = Destination.LocalFiles("/sdcard/Anime")
        nav.navigateTo(folder)
        nav.navigateTo(Destination.Player(PlaybackSource.LocalFile("movie.mkv", 5000L)))

        val popped = nav.popTo { it !is Destination.Player }
        assertTrue(popped)
        assertEquals(folder, nav.current)
        assertEquals(1, nav.stackDepth)
    }

    @Test
    fun popToFallsBackToHomeWhenNoMatchingDestinationInStack() {
        val nav = AppNavigator()
        // If launched directly into Player from an external intent
        nav.replaceTop(Destination.Player(PlaybackSource.LocalFile("external.mp4", 100L)))

        val popped = nav.popTo { it !is Destination.Player }
        assertTrue(popped)
        assertEquals(Destination.Home, nav.current)
        assertEquals(0, nav.stackDepth)
    }

    @Test
    fun popToReturnsFalseIfPredicateAlreadyMatchesCurrent() {
        val nav = AppNavigator()
        val folder = Destination.LocalFiles("/sdcard/Downloads")
        nav.navigateTo(folder)

        val popped = nav.popTo { it is Destination.LocalFiles }
        assertFalse(popped)
        assertEquals(folder, nav.current)
    }

    @Test
    fun backReturnsFalseWhenAlreadyAtHome() {
        val nav = AppNavigator()
        assertFalse(nav.back())
        assertEquals(Destination.Home, nav.current)
    }

    @Test
    fun backToHomeClearsEntireStack() {
        val nav = AppNavigator()
        nav.navigateTo(Destination.LocalFiles("/a"))
        nav.navigateTo(Destination.LocalFiles("/a/b"))
        nav.navigateTo(Destination.LocalFiles("/a/b/c"))
        assertEquals(3, nav.stackDepth)

        nav.backToHome()
        assertEquals(Destination.Home, nav.current)
        assertEquals(0, nav.stackDepth)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `switching section via backToHome followed by navigateTo leaves stack as Home and destination`() {
        val nav = AppNavigator()
        // Navega profundamente dentro de arquivos locais
        nav.navigateTo(Destination.LocalFiles("/storage"))
        nav.navigateTo(Destination.LocalFiles("/storage/movies"))
        nav.navigateTo(Destination.LocalFiles("/storage/movies/anime"))
        nav.navigateTo(Destination.LocalFiles("/storage/movies/anime/series1"))

        assertEquals(5, nav.stack.size)

        // Simula clique na sidebar para 'Playlists'
        nav.backToHome()
        nav.navigateTo(Destination.Playlists)

        assertEquals(listOf(Destination.Home, Destination.Playlists), nav.stack)
        assertEquals(Destination.Playlists, nav.current)
        assertTrue(nav.canGoBack)

        // Voltar leva de volta para a Home
        assertTrue(nav.back())
        assertEquals(Destination.Home, nav.current)
        assertEquals(listOf(Destination.Home), nav.stack)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `switching section via selectSection from arbitrary depth leaves stack as Home and destination`() {
        val nav = AppNavigator()
        nav.navigateTo(Destination.LocalFiles("/folder1"))
        nav.navigateTo(Destination.LocalFiles("/folder1/folder2"))
        nav.navigateTo(Destination.LocalFiles("/folder1/folder2/folder3"))

        nav.selectSection(Destination.NetworkHome)

        assertEquals(listOf(Destination.Home, Destination.NetworkHome), nav.stack)
        assertEquals(Destination.NetworkHome, nav.current)
        assertTrue(nav.canGoBack)

        // Agora troca de seção novamente para Downloads a partir da profundidade de Network
        nav.navigateTo(Destination.Downloads)
        nav.selectSection(Destination.Settings)

        assertEquals(listOf(Destination.Home, Destination.Settings), nav.stack)
        assertEquals(Destination.Settings, nav.current)
    }

    @Test
    fun `selecting Home section from arbitrary depth resets stack to only Home`() {
        val nav = AppNavigator()
        nav.navigateTo(Destination.LocalFiles("/folder1"))
        nav.navigateTo(Destination.LocalFiles("/folder1/folder2"))

        nav.selectSection(Destination.Home)

        assertEquals(listOf(Destination.Home), nav.stack)
        assertEquals(Destination.Home, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `selecting same section replaces deep stack with shallow Home and destination`() {
        val nav = AppNavigator()
        nav.navigateTo(Destination.LocalFiles("/root"))
        nav.navigateTo(Destination.LocalFiles("/root/sub1"))
        nav.navigateTo(Destination.LocalFiles("/root/sub1/sub2"))

        // Re-selecionar arquivos locais na sidebar reseta para a raiz de arquivos
        val target = Destination.LocalFiles("/root")
        nav.selectSection(target)

        assertEquals(listOf(Destination.Home, target), nav.stack)
        assertEquals(target, nav.current)
    }
}
