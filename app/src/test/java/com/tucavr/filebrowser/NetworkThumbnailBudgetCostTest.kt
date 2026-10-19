package com.tucavr.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The budget must come from the dimensions of the single native open, never from the 1080p
// fallback: budgeting an 8K decode as 1080p admits ~30 concurrent decodes and caused the OOM.
class NetworkThumbnailBudgetCostTest {
    private val fallback1080pCost = MemoryBudgetGate.calculateCostBytes(0, 0)

    @Test
    fun `8K probed dimensions select the real 8K cost`() {
        val cost = NetworkThumbnailGenerator.costBytesForProbedDimensions(7680, 4320)

        assertEquals(MemoryBudgetGate.calculateCostBytes(7680, 4320), cost)
        assertEquals(497_664_000L, cost)
        assertNotEquals(fallback1080pCost, cost)
    }

    @Test
    fun `1080p probed dimensions select the 1080p cost`() {
        assertEquals(fallback1080pCost, NetworkThumbnailGenerator.costBytesForProbedDimensions(1920, 1080))
    }

    @Test
    fun `unknown dimensions are never budgeted as the 1080p fallback`() {
        listOf(0 to 0, 0 to 1080, 1920 to 0, -1 to -1).forEach { (w, h) ->
            val cost = NetworkThumbnailGenerator.costBytesForProbedDimensions(w, h)

            assertNotEquals("$w x $h", fallback1080pCost, cost)
            assertTrue("$w x $h must cost at least an 8K frame", cost >= MemoryBudgetGate.calculateCostBytes(7680, 4320))
        }
    }

    @Test
    fun `an unknown-dimension decode fits the default budget so it is not starved`() {
        val cost = NetworkThumbnailGenerator.costBytesForProbedDimensions(0, 0)

        assertTrue(cost <= MemoryBudgetGate.DEFAULT_BUDGET_BYTES)
    }
}
