package com.tucavr.debug

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationExitInfoReporterTest {

    private fun summary(reasonCode: Int, timestampMs: Long, pid: Int = 1234, description: String = "") =
        ApplicationExitInfoReporter.ExitReasonSummary(reasonCode, timestampMs, pid, description)

    @Test
    fun anrAndNativeCrashAreRelevantOthersAreNot() {
        assertTrue(ApplicationExitInfoReporter.isRelevantReason(ApplicationExitInfo.REASON_ANR))
        assertTrue(ApplicationExitInfoReporter.isRelevantReason(ApplicationExitInfo.REASON_CRASH_NATIVE))
        // JVM crash já é capturado pelo handler existente em VRActivity — não é uma lacuna.
        assertFalse(ApplicationExitInfoReporter.isRelevantReason(ApplicationExitInfo.REASON_CRASH))
        assertFalse(ApplicationExitInfoReporter.isRelevantReason(ApplicationExitInfo.REASON_USER_REQUESTED))
    }

    @Test
    fun filterNewRelevantExcludesOldAndIrrelevantEntries() {
        val all = listOf(
            summary(ApplicationExitInfo.REASON_ANR, timestampMs = 1000L),
            summary(ApplicationExitInfo.REASON_CRASH_NATIVE, timestampMs = 500L), // antigo demais
            summary(ApplicationExitInfo.REASON_USER_REQUESTED, timestampMs = 2000L), // irrelevante
            summary(ApplicationExitInfo.REASON_CRASH_NATIVE, timestampMs = 3000L)
        )

        val result = ApplicationExitInfoReporter.filterNewRelevant(all, lastSeenTimestampMs = 900L)

        assertEquals(2, result.size)
        assertEquals(1000L, result[0].timestampMs)
        assertEquals(3000L, result[1].timestampMs)
    }

    @Test
    fun filterNewRelevantReturnsEmptyWhenNothingIsNewer() {
        val all = listOf(summary(ApplicationExitInfo.REASON_ANR, timestampMs = 500L))
        assertTrue(ApplicationExitInfoReporter.filterNewRelevant(all, lastSeenTimestampMs = 1000L).isEmpty())
    }

    @Test
    fun filterNewRelevantSortsChronologically() {
        val all = listOf(
            summary(ApplicationExitInfo.REASON_ANR, timestampMs = 3000L),
            summary(ApplicationExitInfo.REASON_CRASH_NATIVE, timestampMs = 1000L)
        )
        val result = ApplicationExitInfoReporter.filterNewRelevant(all, lastSeenTimestampMs = 0L)
        assertEquals(listOf(1000L, 3000L), result.map { it.timestampMs })
    }

    @Test
    fun formatReportIncludesReasonNameTimestampPidAndDescription() {
        val report = ApplicationExitInfoReporter.formatReport(
            listOf(summary(ApplicationExitInfo.REASON_ANR, timestampMs = 1234L, pid = 999, description = "input dispatch timed out"))
        )
        assertTrue(report.contains("ANR"))
        assertTrue(report.contains("1234"))
        assertTrue(report.contains("999"))
        assertTrue(report.contains("input dispatch timed out"))
    }

    @Test
    fun reasonNameMapsKnownCodes() {
        assertEquals("ANR", ApplicationExitInfoReporter.reasonName(ApplicationExitInfo.REASON_ANR))
        assertEquals("CRASH_NATIVE", ApplicationExitInfoReporter.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("CRASH_JVM", ApplicationExitInfoReporter.reasonName(ApplicationExitInfo.REASON_CRASH))
        assertTrue(ApplicationExitInfoReporter.reasonName(9999).startsWith("OTHER"))
    }
}
