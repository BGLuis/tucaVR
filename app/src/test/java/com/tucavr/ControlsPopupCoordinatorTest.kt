package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ControlsPopupCoordinator] managing the exclusive single-menu popup state
 * machine for the player control bar (Issue #97).
 */
class ControlsPopupCoordinatorTest {
    private lateinit var coordinator: ControlsPopupCoordinator

    @Before
    fun setUp() {
        coordinator = ControlsPopupCoordinator()
    }

    @Test
    fun `initially has no popup open and null active menu`() {
        assertFalse(coordinator.hasOpenPopup)
        assertNull(coordinator.activeMenu)
    }

    @Test
    fun `toggleMenu opens the menu and updates activeMenu and hasOpenPopup`() {
        val opened = coordinator.toggleMenu(ControlsMenuType.FORMAT_3D)

        assertTrue(opened)
        assertTrue(coordinator.hasOpenPopup)
        assertEquals(ControlsMenuType.FORMAT_3D, coordinator.activeMenu)
    }

    @Test
    fun `toggleMenu with the same menu closes it`() {
        coordinator.toggleMenu(ControlsMenuType.FORMAT_3D)
        val reopened = coordinator.toggleMenu(ControlsMenuType.FORMAT_3D)

        assertFalse(reopened)
        assertFalse(coordinator.hasOpenPopup)
        assertNull(coordinator.activeMenu)
    }

    @Test
    fun `toggleMenu with a different menu switches directly to the new menu`() {
        coordinator.toggleMenu(ControlsMenuType.FORMAT_3D)
        val switched = coordinator.toggleMenu(ControlsMenuType.VOLUME)

        assertTrue(switched)
        assertTrue(coordinator.hasOpenPopup)
        assertEquals(ControlsMenuType.VOLUME, coordinator.activeMenu)
    }

    @Test
    fun `dismiss closes open popup and returns true`() {
        coordinator.toggleMenu(ControlsMenuType.SPEED)
        assertTrue(coordinator.hasOpenPopup)

        val dismissed = coordinator.dismiss()

        assertTrue(dismissed)
        assertFalse(coordinator.hasOpenPopup)
        assertNull(coordinator.activeMenu)
    }

    @Test
    fun `dismiss when no popup is open returns false`() {
        val dismissed = coordinator.dismiss()

        assertFalse(dismissed)
        assertFalse(coordinator.hasOpenPopup)
        assertNull(coordinator.activeMenu)
    }

    @Test
    fun `all ControlsMenuType entries can be opened and closed individually`() {
        for (menuType in ControlsMenuType.values()) {
            val opened = coordinator.toggleMenu(menuType)
            assertTrue("Expected $menuType to open", opened)
            assertTrue("Expected hasOpenPopup for $menuType", coordinator.hasOpenPopup)
            assertEquals(menuType, coordinator.activeMenu)

            val dismissed = coordinator.dismiss()
            assertTrue("Expected $menuType to dismiss", dismissed)
            assertFalse("Expected hasOpenPopup to be false after dismiss", coordinator.hasOpenPopup)
            assertNull(coordinator.activeMenu)
        }
    }

    @Test
    fun `sequential switching across multiple menus keeps only the latest open`() {
        val menuSequence =
            listOf(
                ControlsMenuType.FORMAT_3D,
                ControlsMenuType.SCREEN,
                ControlsMenuType.ENVIRONMENT,
                ControlsMenuType.SUBTITLES,
                ControlsMenuType.AUDIO,
                ControlsMenuType.SPEED,
                ControlsMenuType.VOLUME,
                ControlsMenuType.PLAYLIST,
            )

        for (menu in menuSequence) {
            val opened = coordinator.toggleMenu(menu)
            assertTrue("Expected $menu to open", opened)
            assertTrue(coordinator.hasOpenPopup)
            assertEquals(menu, coordinator.activeMenu)
        }

        assertEquals(ControlsMenuType.PLAYLIST, coordinator.activeMenu)
        assertTrue(coordinator.dismiss())
        assertFalse(coordinator.hasOpenPopup)
    }

    @Test
    fun `back button priority simulation intercepts and consumes back event before navigation`() {
        var outerBackNavigated = false

        fun handleBackEvent(coordinator: ControlsPopupCoordinator): Boolean {
            return if (coordinator.hasOpenPopup) {
                coordinator.dismiss()
            } else {
                outerBackNavigated = true
                true
            }
        }

        // Case 1: Popup is open -> Back button closes the popup, no outer back navigation
        coordinator.toggleMenu(ControlsMenuType.SCREEN)
        assertTrue(coordinator.hasOpenPopup)

        val handledWhenOpen = handleBackEvent(coordinator)
        assertTrue(handledWhenOpen)
        assertFalse("Popup should be closed after back", coordinator.hasOpenPopup)
        assertFalse("Outer back should NOT have been triggered while popup was open", outerBackNavigated)

        // Case 2: Popup is now closed -> Next back press triggers outer navigation
        val handledWhenClosed = handleBackEvent(coordinator)
        assertTrue(handledWhenClosed)
        assertTrue("Outer back should be triggered when no popup is open", outerBackNavigated)
    }
}
