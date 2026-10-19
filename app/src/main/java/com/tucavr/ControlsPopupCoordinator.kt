package com.tucavr

/**
 * Menu types supported by the single-popup player controls bar (Issue #97).
 */
enum class ControlsMenuType {
    FORMAT_3D,
    SCREEN,
    ENVIRONMENT,
    SUBTITLES,
    AUDIO,
    SPEED,
    VOLUME,
    PLAYLIST,
}

/**
 * Coordinates single-popup-at-a-time navigation for VR controls (Issue #97).
 * Pure Kotlin logic, fully testable on JVM without Android UI or Robolectric.
 */
class ControlsPopupCoordinator {
    var activeMenu: ControlsMenuType? = null
        private set

    val hasOpenPopup: Boolean
        get() = activeMenu != null

    /**
     * Toggles a menu. If the specified menu is currently open, it is dismissed.
     * If another menu (or no menu) is open, it switches to the requested menu.
     *
     * @return true if the menu was opened, false if it was toggled closed.
     */
    fun toggleMenu(type: ControlsMenuType): Boolean {
        return if (activeMenu == type) {
            dismiss()
            false
        } else {
            activeMenu = type
            true
        }
    }

    /**
     * Dismisses the active popup menu if one is currently open.
     *
     * @return true if a popup was dismissed, false if none was open.
     */
    fun dismiss(): Boolean {
        return if (activeMenu != null) {
            activeMenu = null
            true
        } else {
            false
        }
    }
}
