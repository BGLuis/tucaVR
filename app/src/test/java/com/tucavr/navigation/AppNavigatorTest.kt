package com.tucavr.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AppNavigator].
 * Guards back-stack integrity, single-player depth during playlist advance,
 * and proper stack clearing on stopPlayback (Issues #82 and #83).
 */
class AppNavigatorTest {
    @Test
    fun initialStateStartsAtHomeWithEmptyStack() {
        val nav = AppNavigator()
        assertEquals(Destination.Home, nav.current)
        assertEquals(0, nav.stackDepth)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun navigateToPushesPreviousDestinationAndUpdatesCurrent() {
        val nav = AppNavigator()
        val localFiles = Destination.LocalFiles("/sdcard/Movies")

        nav.navigateTo(localFiles)

        assertEquals(localFiles, nav.current)
        assertEquals(1, nav.stackDepth)
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
}
