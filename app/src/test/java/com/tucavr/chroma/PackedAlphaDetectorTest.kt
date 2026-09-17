package com.tucavr.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackedAlphaDetectorTest {

    @Test
    fun testUserVideoDetectedAsPackedAlpha() {
        val filename = "ARPorn_Daisy Foxxx_Session Off the Books_4096p_8K_FISHEYE190_alpha.mp4"
        assertTrue(PackedAlphaDetector.isPackedAlpha(filename))
        assertEquals(PassthroughMaskMode.PACKED_ALPHA, PackedAlphaDetector.detectMaskMode(filename))
    }

    @Test
    fun testVariousAlphaNamingConventions() {
        val positiveCases = listOf(
            "video_180_alpha.mp4",
            "scene_FISHEYE190_ALPHA.mp4",
            "clip-alpha.mkv",
            "show_alpha_190.mp4",
            "test.alpha.mp4",
            "dancer_alphapacked.mp4",
            "vr_alpha_packed_8k.mp4",
            "/server/torrent/smut/scene_alpha.mp4",
            "C:\\Videos\\VR\\demo_alpha.mp4"
        )
        for (name in positiveCases) {
            assertTrue("Deveria detectar como Packed Alpha: $name", PackedAlphaDetector.isPackedAlpha(name))
            assertEquals(PassthroughMaskMode.PACKED_ALPHA, PackedAlphaDetector.detectMaskMode(name))
        }
    }

    @Test
    fun testNonAlphaVideosNotDetected() {
        val negativeCases = listOf(
            "big_buck_bunny_1080p.mp4",
            "concert_fisheye190_sbs.mp4",
            "alphabet_documentary.mp4",
            "alphonso_in_paris.mp4",
            "alpha.mp4", // Apenas o nome alpha sem sufixo ou tag
            "",
            null
        )
        for (name in negativeCases) {
            assertFalse("NÃO deveria detectar como Packed Alpha: $name", PackedAlphaDetector.isPackedAlpha(name))
            assertEquals(PassthroughMaskMode.OFF, PackedAlphaDetector.detectMaskMode(name))
        }
    }

    @Test
    fun testPassthroughMaskModeEnumValues() {
        assertEquals(0, PassthroughMaskMode.OFF.id)
        assertEquals(1, PassthroughMaskMode.CHROMA_KEY.id)
        assertEquals(2, PassthroughMaskMode.PACKED_ALPHA.id)

        assertEquals(PassthroughMaskMode.OFF, PassthroughMaskMode.fromId(0))
        assertEquals(PassthroughMaskMode.CHROMA_KEY, PassthroughMaskMode.fromId(1))
        assertEquals(PassthroughMaskMode.PACKED_ALPHA, PassthroughMaskMode.fromId(2))
        assertEquals(PassthroughMaskMode.OFF, PassthroughMaskMode.fromId(99))
    }
}
