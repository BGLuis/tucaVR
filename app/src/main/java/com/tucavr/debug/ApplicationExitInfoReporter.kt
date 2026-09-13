package com.tucavr.debug

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F8 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md, seção 8.2): "a maior lacuna de dados do
 * app inteiro, e a mais barata" — ANR e crash nativo (inclusive o abort do ART no teardown
 * Vulkan documentado em outro relatório) não produzem nada em `getExternalFilesDir("debug")`
 * hoje, porque o handler em [com.tucavr.VRActivity] só captura exceções da JVM em threads que
 * o próprio app possui (`Thread.setDefaultUncaughtExceptionHandler`). `ApplicationExitInfo` é
 * lida uma vez no arranque seguinte — custa zero em runtime — e cobre essas duas classes de
 * morte do processo que hoje são invisíveis.
 *
 * `[ExitReasonSummary]`/[formatReport]/[reasonName] são puros (sem `Context`/framework) de
 * propósito — [android.app.ApplicationExitInfo] é uma classe do framework sem construtor
 * público, então a lógica de filtragem/formatação vive aqui, testável com JUnit puro na JVM,
 * separada de [checkAndReport] (que só chama o framework e escreve arquivo).
 */
object ApplicationExitInfoReporter {
    private const val PREFS_NAME = "application_exit_info_reporter"
    private const val KEY_LAST_SEEN_TIMESTAMP_MS = "last_seen_timestamp_ms"

    data class ExitReasonSummary(
        val reasonCode: Int,
        val timestampMs: Long,
        val pid: Int,
        val description: String
    )

    fun reasonName(reasonCode: Int): String = when (reasonCode) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_CRASH -> "CRASH_JVM"
        else -> "OTHER($reasonCode)"
    }

    /** Só estas duas classes de morte são hoje invisíveis (JVM crash já é capturado pelo
     * handler existente em VRActivity) — ver rationale na doc da classe. */
    fun isRelevantReason(reasonCode: Int): Boolean =
        reasonCode == ApplicationExitInfo.REASON_ANR || reasonCode == ApplicationExitInfo.REASON_CRASH_NATIVE

    /** Filtra por [isRelevantReason] e por mais recente que [lastSeenTimestampMs] — evita
     * reportar o mesmo evento a cada reinício do app enquanto o histórico do sistema não gira. */
    fun filterNewRelevant(all: List<ExitReasonSummary>, lastSeenTimestampMs: Long): List<ExitReasonSummary> =
        all.filter { it.timestampMs > lastSeenTimestampMs && isRelevantReason(it.reasonCode) }
            .sortedBy { it.timestampMs }

    fun formatReport(summaries: List<ExitReasonSummary>): String {
        val sb = StringBuilder()
        sb.appendLine("ApplicationExitInfo — ${summaries.size} evento(s) desde o último arranque processado")
        for (s in summaries) {
            sb.appendLine("---")
            sb.appendLine("Reason: ${reasonName(s.reasonCode)} (${s.reasonCode})")
            sb.appendLine("Timestamp: ${s.timestampMs}")
            sb.appendLine("PID: ${s.pid}")
            sb.appendLine("Description: ${s.description}")
        }
        return sb.toString()
    }

    /**
     * Lê o histórico do sistema, filtra o que é novo e relevante, grava um relatório de texto
     * em `getExternalFilesDir("debug")` (mesma convenção dos `crash-*.txt` existentes) e, a
     * partir da API 31, o tombstone bruto em protobuf de cada evento (não parseado — ver 8.4
     * do relatório: análise de protobuf fica para ferramenta externa). Chamar uma vez por
     * arranque; sem custo de runtime além disso.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun checkAndReport(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN_TIMESTAMP_MS, 0L)

        val all = try {
            am.getHistoricalProcessExitReasons(null, 0, 0)
        } catch (e: Exception) {
            VRLog.w("ApplicationExitInfoReporter: falha ao ler histórico de saída", e)
            return
        }

        val allSummaries = all.map {
            ExitReasonSummary(it.reason, it.timestamp, it.pid, it.description ?: "")
        }
        val relevant = filterNewRelevant(allSummaries, lastSeen)
        if (relevant.isEmpty()) return

        val debugDir = context.getExternalFilesDir("debug")
        if (debugDir != null) {
            try {
                if (!debugDir.exists()) debugDir.mkdirs()
                val timeStr = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(debugDir, "exit-info-$timeStr.txt").writeText(formatReport(relevant))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val relevantTimestamps = relevant.map { it.timestampMs }.toSet()
                    var i = 0
                    for (info in all) {
                        if (info.timestamp !in relevantTimestamps) continue
                        info.traceInputStream?.use { input ->
                            File(debugDir, "exit-info-$timeStr-$i.pb").outputStream().use { out -> input.copyTo(out) }
                        }
                        i++
                    }
                }
            } catch (e: Exception) {
                VRLog.w("ApplicationExitInfoReporter: falha ao gravar relatório", e)
            }
        }

        prefs.edit().putLong(KEY_LAST_SEEN_TIMESTAMP_MS, relevant.maxOf { it.timestampMs }).apply()
    }
}
