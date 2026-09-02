package com.tucavr.codec

import com.tucavr.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Testes unitários para [CodecCapabilityManager] rodando na JVM padrão.
 */
class CodecCapabilityManagerTest {

    private class FakeCodecInfoProvider(
        private val decoders: List<CodecDecoderInfo>
    ) : CodecInfoProvider {
        override fun getDecoders(): List<CodecDecoderInfo> = decoders
    }

    @Before
    fun setUp() {
        CodecCapabilityManager.refresh()
    }

    @After
    fun tearDown() {
        CodecCapabilityManager.provider = SystemCodecInfoProvider()
        CodecCapabilityManager.refresh()
    }

    @Test
    fun `normalizeMimeType maps supported aliases and casing correctly`() {
        assertEquals(CodecCapabilityManager.MIME_AV1, CodecCapabilityManager.normalizeMimeType("av1"))
        assertEquals(CodecCapabilityManager.MIME_AV1, CodecCapabilityManager.normalizeMimeType("AV1"))
        assertEquals(CodecCapabilityManager.MIME_AV1, CodecCapabilityManager.normalizeMimeType("av01"))
        assertEquals(CodecCapabilityManager.MIME_AV1, CodecCapabilityManager.normalizeMimeType("video/av01"))

        assertEquals(CodecCapabilityManager.MIME_VP9, CodecCapabilityManager.normalizeMimeType("vp9"))
        assertEquals(CodecCapabilityManager.MIME_VP9, CodecCapabilityManager.normalizeMimeType("VP9"))
        assertEquals(CodecCapabilityManager.MIME_VP9, CodecCapabilityManager.normalizeMimeType("vp09"))
        assertEquals(CodecCapabilityManager.MIME_VP9, CodecCapabilityManager.normalizeMimeType("video/x-vnd.on2.vp9"))

        assertEquals(CodecCapabilityManager.MIME_AVC, CodecCapabilityManager.normalizeMimeType("h264"))
        assertEquals(CodecCapabilityManager.MIME_AVC, CodecCapabilityManager.normalizeMimeType("H.264"))
        assertEquals(CodecCapabilityManager.MIME_AVC, CodecCapabilityManager.normalizeMimeType("avc"))
        assertEquals(CodecCapabilityManager.MIME_AVC, CodecCapabilityManager.normalizeMimeType("video/avc"))

        assertEquals(CodecCapabilityManager.MIME_HEVC, CodecCapabilityManager.normalizeMimeType("hevc"))
        assertEquals(CodecCapabilityManager.MIME_HEVC, CodecCapabilityManager.normalizeMimeType("h265"))
        assertEquals(CodecCapabilityManager.MIME_HEVC, CodecCapabilityManager.normalizeMimeType("H.265"))
        assertEquals(CodecCapabilityManager.MIME_HEVC, CodecCapabilityManager.normalizeMimeType("video/hevc"))

        assertNull(CodecCapabilityManager.normalizeMimeType("unknown_codec"))
        assertNull(CodecCapabilityManager.normalizeMimeType(""))
    }

    @Test
    fun `av1 with hardware acceleration is recognized as supported`() {
        val fakeDecoders = listOf(
            CodecDecoderInfo(
                mimeType = CodecCapabilityManager.MIME_AV1,
                codecName = "c2.qti.av1.decoder",
                isHardwareAccelerated = true,
                isSoftwareOnly = false,
                isVendor = true
            ),
            CodecDecoderInfo(
                mimeType = CodecCapabilityManager.MIME_AVC,
                codecName = "c2.qti.avc.decoder",
                isHardwareAccelerated = true,
                isSoftwareOnly = false,
                isVendor = true
            )
        )
        CodecCapabilityManager.provider = FakeCodecInfoProvider(fakeDecoders)

        assertTrue(CodecCapabilityManager.isHardwareSupported("av1"))
        assertEquals("c2.qti.av1.decoder", CodecCapabilityManager.getDecoderName("av1"))

        val validation = CodecCapabilityManager.validatePlaybackSupport("av1")
        assertTrue(validation.isPlayable)
        assertTrue(validation.isHardwareAccelerated)
        assertEquals("c2.qti.av1.decoder", validation.decoderName)
        assertNull(validation.errorMessageResId)
    }

    @Test
    fun `av1 with software only decoder returns clear hardware unsupported error`() {
        val fakeDecoders = listOf(
            CodecDecoderInfo(
                mimeType = CodecCapabilityManager.MIME_AV1,
                codecName = "c2.android.av1.decoder",
                isHardwareAccelerated = false,
                isSoftwareOnly = true,
                isVendor = false
            )
        )
        CodecCapabilityManager.provider = FakeCodecInfoProvider(fakeDecoders)

        assertFalse(CodecCapabilityManager.isHardwareSupported("av1"))
        assertTrue(CodecCapabilityManager.isSupported("av1"))

        val validation = CodecCapabilityManager.validatePlaybackSupport("av1")
        assertFalse(validation.isPlayable)
        assertFalse(validation.isHardwareAccelerated)
        assertEquals(R.string.codec_hw_unsupported_error, validation.errorMessageResId)
        assertEquals("c2.android.av1.decoder", validation.decoderName)
    }

    @Test
    fun `av1 completely missing returns clear hardware unsupported error`() {
        CodecCapabilityManager.provider = FakeCodecInfoProvider(emptyList())

        assertFalse(CodecCapabilityManager.isHardwareSupported("av1"))
        assertFalse(CodecCapabilityManager.isSupported("av1"))
        assertNull(CodecCapabilityManager.getDecoderName("av1"))

        val validation = CodecCapabilityManager.validatePlaybackSupport("av1")
        assertFalse(validation.isPlayable)
        assertFalse(validation.isHardwareAccelerated)
        assertEquals(R.string.codec_hw_unsupported_error, validation.errorMessageResId)
        assertNull(validation.decoderName)
    }

    @Test
    fun `vp9 with hardware acceleration is recognized as supported`() {
        val fakeDecoders = listOf(
            CodecDecoderInfo(
                mimeType = CodecCapabilityManager.MIME_VP9,
                codecName = "OMX.qcom.video.decoder.vp9",
                isHardwareAccelerated = true,
                isSoftwareOnly = false,
                isVendor = true
            )
        )
        CodecCapabilityManager.provider = FakeCodecInfoProvider(fakeDecoders)

        assertTrue(CodecCapabilityManager.isHardwareSupported("vp9"))
        val validation = CodecCapabilityManager.validatePlaybackSupport("vp9")
        assertTrue(validation.isPlayable)
        assertTrue(validation.isHardwareAccelerated)
        assertEquals("OMX.qcom.video.decoder.vp9", validation.decoderName)
    }

    @Test
    fun `vp9 without hardware acceleration returns hardware unsupported error`() {
        val fakeDecoders = listOf(
            CodecDecoderInfo(
                mimeType = CodecCapabilityManager.MIME_VP9,
                codecName = "c2.android.vp9.decoder",
                isHardwareAccelerated = false,
                isSoftwareOnly = true,
                isVendor = false
            )
        )
        CodecCapabilityManager.provider = FakeCodecInfoProvider(fakeDecoders)

        val validation = CodecCapabilityManager.validatePlaybackSupport("vp9")
        assertFalse(validation.isPlayable)
        assertEquals(R.string.codec_hw_unsupported_error, validation.errorMessageResId)
    }

    @Test
    fun `prefers hardware decoder when both hardware and software decoders are present`() {
        val fakeDecoders = listOf(
            CodecDecoderInfo(
                mimeType = CodecCapabilityManager.MIME_AV1,
                codecName = "c2.android.av1.decoder",
                isHardwareAccelerated = false,
                isSoftwareOnly = true,
                isVendor = false
            ),
            CodecDecoderInfo(
                mimeType = CodecCapabilityManager.MIME_AV1,
                codecName = "c2.qti.av1.decoder",
                isHardwareAccelerated = true,
                isSoftwareOnly = false,
                isVendor = true
            )
        )
        CodecCapabilityManager.provider = FakeCodecInfoProvider(fakeDecoders)

        val decoder = CodecCapabilityManager.findDecoder("av1")
        assertNotNull(decoder)
        assertEquals("c2.qti.av1.decoder", decoder?.codecName)
        assertTrue(decoder?.isHardwareAccelerated == true)
    }

    @Test
    fun `unknown video codec returns generic unsupported error`() {
        CodecCapabilityManager.provider = FakeCodecInfoProvider(emptyList())

        val validation = CodecCapabilityManager.validatePlaybackSupport("prores")
        assertFalse(validation.isPlayable)
        assertEquals(R.string.codec_generic_unsupported_error, validation.errorMessageResId)
    }
}
