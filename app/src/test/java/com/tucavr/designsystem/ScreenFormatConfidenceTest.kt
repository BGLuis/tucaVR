package com.tucavr.designsystem

import com.tucavr.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenFormatConfidenceTest {
    @Test
    fun testConfidenceLabelMappingParityWithRust() {
        // Rust DetectionConfidence enum parity:
        // FromMetadata = 0 -> High confidence
        // FromFilename = 1 -> High confidence
        // FromHeuristic = 2 -> Low confidence (Estimated)
        // FromVisualInspection = 3 -> Medium confidence
        // Default = 4 -> No suffix
        assertEquals(R.string.format_confidence_high, ScreenFormatModal.getConfidenceLabelResId(0))
        assertEquals(R.string.format_confidence_high, ScreenFormatModal.getConfidenceLabelResId(1))
        assertEquals(R.string.format_confidence_low, ScreenFormatModal.getConfidenceLabelResId(2))
        assertEquals(R.string.format_confidence_medium, ScreenFormatModal.getConfidenceLabelResId(3))
        assertNull(ScreenFormatModal.getConfidenceLabelResId(4))
        assertNull(ScreenFormatModal.getConfidenceLabelResId(99))
    }
}
