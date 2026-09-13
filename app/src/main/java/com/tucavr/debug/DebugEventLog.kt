package com.tucavr.debug

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Locale

/**
 * F6 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md, seção 2.2): log de eventos — a série a
 * 1Hz (CSV) não pode ver um stutter de 20ms (90 frames por amostra) e os contadores
 * cumulativos sobrevivem à decimação mas só dizem "quantos", não "quando" nem "com o
 * pipeline em que estado". Um evento por episódio (stutter/freeze/stall/transição de
 * qualidade), com snapshot do pipeline no início — complementa o histograma (F5 G2, dá a
 * distribuição) respondendo "o que estava acontecendo quando travou".
 *
 * [detectEvents]/[formatEventLine] são puros — comparam duas amostras já parseadas
 * ([NativeDebugStats]) e formatam texto, sem `Context`/arquivo — testável com JUnit puro na
 * JVM. [DebugEventLogWriter] é a única parte que toca I/O.
 */
sealed class DebugEvent {
    abstract val bottleneckStage: BottleneckStage
    abstract val snapshot: String

    data class Stutter(val stutterCount: Int, override val bottleneckStage: BottleneckStage, override val snapshot: String) : DebugEvent()
    data class Freeze(val freezeCount: Int, override val bottleneckStage: BottleneckStage, override val snapshot: String) : DebugEvent()
    data class VideoStallEnded(val videoStallCount: Int, val frameGapMsAtDetection: Float, override val bottleneckStage: BottleneckStage, override val snapshot: String) : DebugEvent()
    data class QualityTransition(val fromLevel: String, val toLevel: String, val reason: String, override val bottleneckStage: BottleneckStage, override val snapshot: String) : DebugEvent()
}

object DebugEventLog {

    private fun snapshotOf(stats: NativeDebugStats): String = String.format(
        Locale.US,
        "frame_ms=%.1f gpu_ms=%.2f queue=%d net_mbs=%.2f drift_ms=%.1f quality=%s/%s scale=%.2f",
        stats.frameTimeMs, stats.gpuTimeMs, stats.queueDepth, stats.netMBs, stats.avDriftMs,
        stats.qualityLevel, stats.qualityReason, stats.renderScale
    )

    /**
     * Compara `previous` (amostra anterior, `null` na primeira chamada da sessão) com
     * `current` e devolve os eventos que ocorreram entre as duas — um por contador que
     * avançou, mais transição de qualidade se o nível mudou. `current` fornece o snapshot
     * (estado do pipeline no momento em que o evento foi observado).
     */
    fun detectEvents(previous: NativeDebugStats?, current: NativeDebugStats): List<DebugEvent> {
        if (previous == null) return emptyList()

        val events = mutableListOf<DebugEvent>()
        val stage = BottleneckStageAnalyzer.analyze(current)
        val snapshot = snapshotOf(current)

        if (current.stutterCount > previous.stutterCount) {
            events += DebugEvent.Stutter(current.stutterCount, stage, snapshot)
        }
        if (current.freezeCount > previous.freezeCount) {
            events += DebugEvent.Freeze(current.freezeCount, stage, snapshot)
        }
        // D-02: videoStallCount só incrementa quando um episódio de stall REAL termina (ver
        // vr_player_app_vulkan.cpp) — o momento em que detectamos o incremento é o fim do
        // episódio; a duração real fica no frame_gap_ms da amostra ANTERIOR (o pico antes do
        // contador zerar), não na atual (já resetada).
        if (current.videoStallCount > previous.videoStallCount) {
            events += DebugEvent.VideoStallEnded(current.videoStallCount, previous.frameGapMs, stage, snapshot)
        }
        if (current.qualityLevel != previous.qualityLevel) {
            events += DebugEvent.QualityTransition(previous.qualityLevel, current.qualityLevel, current.qualityReason, stage, snapshot)
        }

        return events
    }

    fun formatEventLine(event: DebugEvent, timestampMs: Long): String {
        val stageStr = event.bottleneckStage.name
        val body = when (event) {
            is DebugEvent.Stutter -> "STUTTER\tcount=${event.stutterCount}"
            is DebugEvent.Freeze -> "FREEZE\tcount=${event.freezeCount}"
            is DebugEvent.VideoStallEnded -> String.format(
                Locale.US, "VIDEO_STALL_ENDED\tcount=%d\tduration_ms=%.0f",
                event.videoStallCount, event.frameGapMsAtDetection
            )
            is DebugEvent.QualityTransition -> "QUALITY_TRANSITION\tfrom=${event.fromLevel}\tto=${event.toLevel}\treason=${event.reason}"
        }
        return "$timestampMs\t$body\tstage=$stageStr\t${event.snapshot}"
    }
}

/**
 * Única parte com I/O: um arquivo de eventos por sessão, mesma convenção de nome dos CSVs de
 * telemetria (`getExternalFilesDir("debug")`) — ver `scripts/collect-debug.sh`.
 */
class DebugEventLogWriter(private val context: Context) {
    private var previousStats: NativeDebugStats? = null
    private var writer: PrintWriter? = null
    private var currentSessionId: String? = null

    fun recordSample(sessionId: String, stats: NativeDebugStats, timestampMs: Long) {
        val events = DebugEventLog.detectEvents(previousStats, stats)
        previousStats = stats
        if (events.isEmpty()) return

        val w = ensureWriter(sessionId) ?: return
        for (event in events) {
            w.println(DebugEventLog.formatEventLine(event, timestampMs))
        }
        w.flush()
    }

    private fun ensureWriter(sessionId: String): PrintWriter? {
        if (currentSessionId == sessionId && writer != null) return writer
        close()
        val debugDir = context.getExternalFilesDir("debug") ?: return null
        return try {
            if (!debugDir.exists()) debugDir.mkdirs()
            val file = File(debugDir, "session-$sessionId-events.log")
            val w = PrintWriter(FileWriter(file, true))
            writer = w
            currentSessionId = sessionId
            w
        } catch (e: Exception) {
            VRLog.w("DebugEventLogWriter: falha ao abrir arquivo de eventos: ${e.message}", e)
            null
        }
    }

    fun close() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            VRLog.w("DebugEventLogWriter: falha ao fechar arquivo de eventos: ${e.message}", e)
        } finally {
            writer = null
            currentSessionId = null
            previousStats = null
        }
    }
}
