package com.tucavr
 
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Testes unitários para a formatação de tempo e minutagem do player VR ([VRControlsPresentation.formatPlaybackTime]).
 */
class VRControlsTimeFormatTest {

    @Test
    fun `formats under a minute`() {
        assertEquals("00:00", VRControlsPresentation.formatPlaybackTime(0f))
        assertEquals("00:07", VRControlsPresentation.formatPlaybackTime(7f))
        assertEquals("00:59", VRControlsPresentation.formatPlaybackTime(59f))
    }

    @Test
    fun `formats minutes and seconds under an hour`() {
        assertEquals("01:05", VRControlsPresentation.formatPlaybackTime(65f))
        assertEquals("12:34", VRControlsPresentation.formatPlaybackTime(12 * 60f + 34f))
        assertEquals("59:59", VRControlsPresentation.formatPlaybackTime(59 * 60f + 59f))
    }

    @Test
    fun `formats with hours when duration is one hour or more`() {
        val twoHourDuration = 7200f
        assertEquals("00:00:00", VRControlsPresentation.formatPlaybackTime(0f, twoHourDuration))
        assertEquals("00:05:30", VRControlsPresentation.formatPlaybackTime(330f, twoHourDuration))
        assertEquals("01:00:00", VRControlsPresentation.formatPlaybackTime(3600f, twoHourDuration))
        assertEquals("01:23:45", VRControlsPresentation.formatPlaybackTime(1 * 3600f + 23 * 60f + 45f, twoHourDuration))
        assertEquals("02:00:00", VRControlsPresentation.formatPlaybackTime(7200f, twoHourDuration))
    }

    @Test
    fun `formats with hours when elapsed time reaches one hour even if duration unknown`() {
        assertEquals("01:00:00", VRControlsPresentation.formatPlaybackTime(3600f, 0f))
        assertEquals("01:45:12", VRControlsPresentation.formatPlaybackTime(1 * 3600f + 45 * 60f + 12f, 0f))
    }

    @Test
    fun `clamps negative time values to zero`() {
        assertEquals("00:00", VRControlsPresentation.formatPlaybackTime(-15f, 500f))
        assertEquals("00:00:00", VRControlsPresentation.formatPlaybackTime(-15f, 4000f))
    }

    @Test
    fun `seekBarMax is configured to ten thousand for high subsecond precision`() {
        assertEquals(10000, VRControlsPresentation.SEEK_BAR_MAX)
    }
}
