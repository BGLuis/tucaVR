package com.tucavr.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugEventLogTest {

    @Test
    fun firstSampleWithNoPreviousProducesNoEvents() {
        val current = NativeDebugStats(stutterCount = 5, freezeCount = 2)
        assertTrue(DebugEventLog.detectEvents(null, current).isEmpty())
    }

    @Test
    fun noChangeProducesNoEvents() {
        val stats = NativeDebugStats(stutterCount = 5, freezeCount = 2, videoStallCount = 1, qualityLevel = "HIGH")
        assertTrue(DebugEventLog.detectEvents(stats, stats).isEmpty())
    }

    @Test
    fun stutterCountIncreaseProducesStutterEvent() {
        val prev = NativeDebugStats(stutterCount = 5)
        val curr = NativeDebugStats(stutterCount = 6)
        val events = DebugEventLog.detectEvents(prev, curr)
        assertEquals(1, events.size)
        assertTrue(events[0] is DebugEvent.Stutter)
        assertEquals(6, (events[0] as DebugEvent.Stutter).stutterCount)
    }

    @Test
    fun freezeCountIncreaseProducesFreezeEvent() {
        val prev = NativeDebugStats(freezeCount = 1)
        val curr = NativeDebugStats(freezeCount = 2)
        val events = DebugEventLog.detectEvents(prev, curr)
        assertEquals(1, events.size)
        assertTrue(events[0] is DebugEvent.Freeze)
    }

    @Test
    fun videoStallCountIncreaseProducesStallEventWithPreviousFrameGapAsDuration() {
        // D-02: a duração do episódio que acabou de terminar é o pico de frame_gap_ms da
        // amostra ANTERIOR (ver comentário em detectEvents) — reproduz o caso real do
        // relatório (§1.2): stall de 34.568,8ms detectado quando o contador incrementa.
        val prev = NativeDebugStats(videoStallCount = 0, frameGapMs = 34568.8f)
        val curr = NativeDebugStats(videoStallCount = 1, frameGapMs = 11.1f)
        val events = DebugEventLog.detectEvents(prev, curr)
        assertEquals(1, events.size)
        val stallEvent = events[0] as DebugEvent.VideoStallEnded
        assertEquals(1, stallEvent.videoStallCount)
        assertEquals(34568.8f, stallEvent.frameGapMsAtDetection, 0.01f)
    }

    @Test
    fun qualityLevelChangeProducesTransitionEvent() {
        val prev = NativeDebugStats(qualityLevel = "HIGH", qualityReason = "NONE")
        val curr = NativeDebugStats(qualityLevel = "MEDIUM", qualityReason = "GpuOverload")
        val events = DebugEventLog.detectEvents(prev, curr)
        assertEquals(1, events.size)
        val transition = events[0] as DebugEvent.QualityTransition
        assertEquals("HIGH", transition.fromLevel)
        assertEquals("MEDIUM", transition.toLevel)
        assertEquals("GpuOverload", transition.reason)
    }

    @Test
    fun multipleSimultaneousChangesProduceMultipleEvents() {
        val prev = NativeDebugStats(stutterCount = 1, freezeCount = 0, qualityLevel = "HIGH")
        val curr = NativeDebugStats(stutterCount = 2, freezeCount = 1, qualityLevel = "LOW")
        val events = DebugEventLog.detectEvents(prev, curr)
        assertEquals(3, events.size)
    }

    @Test
    fun formatEventLineIncludesTimestampAndBottleneckStage() {
        val prev = NativeDebugStats(videoStallCount = 0, frameGapMs = 34568.8f, queueDepth = 90)
        val curr = NativeDebugStats(videoStallCount = 1, frameGapMs = 11.1f, queueDepth = 90)
        val event = DebugEventLog.detectEvents(prev, curr).single()

        // A amostra ANTERIOR (fila cheia + stall) é o que caracteriza o estágio do episódio,
        // mas o snapshot/estágio usados vêm de `current` (ver detectEvents) — este teste só
        // confirma que a linha carrega os campos essenciais para reconstrução do episódio.
        val line = DebugEventLog.formatEventLine(event, timestampMs = 123456789L)
        assertTrue(line.startsWith("123456789\t"))
        assertTrue(line.contains("VIDEO_STALL_ENDED"))
        assertTrue(line.contains("duration_ms=34569") || line.contains("duration_ms=34568"))
        assertTrue(line.contains("stage="))
    }

    @Test
    fun formatEventLineForQualityTransitionIncludesFromToAndReason() {
        val prev = NativeDebugStats(qualityLevel = "HIGH", qualityReason = "NONE")
        val curr = NativeDebugStats(qualityLevel = "MEDIUM", qualityReason = "DroppedFrames")
        val event = DebugEventLog.detectEvents(prev, curr).single()
        val line = DebugEventLog.formatEventLine(event, timestampMs = 1000L)
        assertTrue(line.contains("QUALITY_TRANSITION"))
        assertTrue(line.contains("from=HIGH"))
        assertTrue(line.contains("to=MEDIUM"))
        assertTrue(line.contains("reason=DroppedFrames"))
    }
}
