package com.tucavr.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class BottleneckStageAnalyzerTest {

    @Test
    fun healthyPlaybackReturnsNone() {
        val stats = NativeDebugStats(frameGapMs = 16.7f, queueDepth = 45)
        assertEquals(BottleneckStage.NONE, BottleneckStageAnalyzer.analyze(stats))
    }

    @Test
    fun stalledWithEmptyQueuePointsToNetwork() {
        // Fila vazia + estagno = o demux nao esta recebendo pacotes novos da rede.
        val stats = NativeDebugStats(frameGapMs = 1000f, queueDepth = 0)
        assertEquals(BottleneckStage.NETWORK, BottleneckStageAnalyzer.analyze(stats))
    }

    @Test
    fun stalledWithFullQueuePointsToPresentation() {
        // Reproduz exatamente a sessao real do relatorio (§1.2): frame_gap_ms chegou a
        // 34.568,8ms com video_q_depth = 90 (fila cheia) pelos 34,5s inteiros do stall —
        // pacotes chegavam, mas nada consumia a fila.
        val stats = NativeDebugStats(frameGapMs = 34568.8f, queueDepth = 90)
        assertEquals(BottleneckStage.PRESENTATION, BottleneckStageAnalyzer.analyze(stats))
    }

    @Test
    fun stallJustStartedWithMidQueueIsInconclusive() {
        val stats = NativeDebugStats(frameGapMs = 600f, queueDepth = 40)
        assertEquals(BottleneckStage.NONE, BottleneckStageAnalyzer.analyze(stats))
    }

    @Test
    fun exactlyAtStallThresholdIsNotYetStalled() {
        val stats = NativeDebugStats(
            frameGapMs = BottleneckStageAnalyzer.STALL_THRESHOLD_MS,
            queueDepth = 0
        )
        assertEquals(BottleneckStage.NONE, BottleneckStageAnalyzer.analyze(stats))
    }

    @Test
    fun queueThresholdsAreInclusive() {
        val emptyBoundary = NativeDebugStats(
            frameGapMs = 501f,
            queueDepth = BottleneckStageAnalyzer.QUEUE_EMPTY_THRESHOLD
        )
        assertEquals(BottleneckStage.NETWORK, BottleneckStageAnalyzer.analyze(emptyBoundary))

        val fullBoundary = NativeDebugStats(
            frameGapMs = 501f,
            queueDepth = BottleneckStageAnalyzer.QUEUE_FULL_THRESHOLD
        )
        assertEquals(BottleneckStage.PRESENTATION, BottleneckStageAnalyzer.analyze(fullBoundary))
    }
}
