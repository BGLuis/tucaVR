package com.tucavr

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalMonitorTest {

    @Test
    fun testNoneAndLightMapToNormalWithEmptyActions() {
        val stateNone = ThermalMonitor.mapStatusToState(PowerManager.THERMAL_STATUS_NONE)
        assertEquals(ThermalMonitor.ThermalLevel.NORMAL, stateNone.level)
        assertTrue(stateNone.actions.isEmpty())

        val stateLight = ThermalMonitor.mapStatusToState(PowerManager.THERMAL_STATUS_LIGHT)
        assertEquals(ThermalMonitor.ThermalLevel.NORMAL, stateLight.level)
        assertTrue(stateLight.actions.isEmpty())
    }

    @Test
    fun testModerateMapsToSimplifyEnvironmentAndPausePrefetch() {
        val state = ThermalMonitor.mapStatusToState(PowerManager.THERMAL_STATUS_MODERATE)
        assertEquals(ThermalMonitor.ThermalLevel.MODERATE, state.level)
        assertTrue(state.actions.contains(ThermalMonitor.ThermalAction.SIMPLIFY_ENVIRONMENT))
        assertTrue(state.actions.contains(ThermalMonitor.ThermalAction.PAUSE_PREFETCH))
        assertFalse(state.actions.contains(ThermalMonitor.ThermalAction.PAUSE_PLAYBACK))
    }

    @Test
    fun testSevereMapsToResolutionReduceFpsLimitAndWarnUser() {
        val state = ThermalMonitor.mapStatusToState(PowerManager.THERMAL_STATUS_SEVERE)
        assertEquals(ThermalMonitor.ThermalLevel.SEVERE, state.level)
        assertTrue(state.actions.contains(ThermalMonitor.ThermalAction.REDUCE_RENDER_RESOLUTION))
        assertTrue(state.actions.contains(ThermalMonitor.ThermalAction.SIMPLIFY_ENVIRONMENT))
        assertTrue(state.actions.contains(ThermalMonitor.ThermalAction.LIMIT_FPS))
        assertTrue(state.actions.contains(ThermalMonitor.ThermalAction.WARN_USER))
        assertFalse(state.actions.contains(ThermalMonitor.ThermalAction.PAUSE_PLAYBACK))
    }

    @Test
    fun testCriticalMapsToPausePlaybackAndWarnUser() {
        val state = ThermalMonitor.mapStatusToState(PowerManager.THERMAL_STATUS_CRITICAL)
        assertEquals(ThermalMonitor.ThermalLevel.CRITICAL, state.level)
        assertTrue(state.actions.contains(ThermalMonitor.ThermalAction.PAUSE_PLAYBACK))
        assertTrue(state.actions.contains(ThermalMonitor.ThermalAction.WARN_USER))
    }

    @Test
    fun testEmergencyAndShutdownMapToShutdownLevel() {
        val stateEmergency = ThermalMonitor.mapStatusToState(PowerManager.THERMAL_STATUS_EMERGENCY)
        assertEquals(ThermalMonitor.ThermalLevel.SHUTDOWN, stateEmergency.level)
        assertTrue(stateEmergency.actions.contains(ThermalMonitor.ThermalAction.PAUSE_PLAYBACK))

        val stateShutdown = ThermalMonitor.mapStatusToState(PowerManager.THERMAL_STATUS_SHUTDOWN)
        assertEquals(ThermalMonitor.ThermalLevel.SHUTDOWN, stateShutdown.level)
        assertTrue(stateShutdown.actions.contains(ThermalMonitor.ThermalAction.PAUSE_PLAYBACK))
    }

    @Test
    fun testUnknownStatusDefaultsToNormal() {
        val stateUnknown = ThermalMonitor.mapStatusToState(999)
        assertEquals(ThermalMonitor.ThermalLevel.NORMAL, stateUnknown.level)
        assertTrue(stateUnknown.actions.isEmpty())
    }
}
