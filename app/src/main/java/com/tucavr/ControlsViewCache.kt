package com.tucavr

/**
 * Pure Kotlin cache that tracks rendered player controls values to prevent redundant View writes
 * and VirtualDisplay invalidations (Issue #152).
 */
class ControlsViewCache {
    var lastProgress: Int? = null
        private set
    var lastTimeText: String? = null
        private set
    var lastTotalTimeText: String? = null
        private set
    var lastPlaying: Boolean? = null
        private set

    /**
     * Checks if the seek bar progress changed and updates the cached value if so.
     * @return true if the progress changed and the view should be written.
     */
    fun shouldWriteProgress(newProgress: Int): Boolean {
        if (lastProgress == newProgress) return false
        lastProgress = newProgress
        return true
    }

    /**
     * Checks if the current time text changed and updates the cached value if so.
     * @return true if the time text changed and the view should be written.
     */
    fun shouldWriteTime(newText: String): Boolean {
        if (lastTimeText == newText) return false
        lastTimeText = newText
        return true
    }

    /**
     * Checks if the total time text changed and updates the cached value if so.
     * @return true if the total time text changed and the view should be written.
     */
    fun shouldWriteTotalTime(newText: String): Boolean {
        if (lastTotalTimeText == newText) return false
        lastTotalTimeText = newText
        return true
    }

    /**
     * Checks if the play/pause state changed and updates the cached value if so.
     * @return true if the playback state changed and the view should be written.
     */
    fun shouldWritePlaying(isPlaying: Boolean): Boolean {
        if (lastPlaying == isPlaying) return false
        lastPlaying = isPlaying
        return true
    }

    fun shouldUpdateProgress(newProgress: Int): Boolean = shouldWriteProgress(newProgress)

    fun shouldUpdateTime(newText: String): Boolean = shouldWriteTime(newText)

    fun shouldUpdateTotalTime(newText: String): Boolean = shouldWriteTotalTime(newText)

    fun shouldUpdatePlaying(isPlaying: Boolean): Boolean = shouldWritePlaying(isPlaying)

    /**
     * Clears all cached values so the next update calls will write to the views again (e.g. after reset).
     */
    fun reset() {
        lastProgress = null
        lastTimeText = null
        lastTotalTimeText = null
        lastPlaying = null
    }
}
