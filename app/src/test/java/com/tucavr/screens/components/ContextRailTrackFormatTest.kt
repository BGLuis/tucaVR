package com.tucavr.screens.components

import com.tucavr.filebrowser.TrackInfo
import com.tucavr.filebrowser.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextRailTrackFormatTest {
    private fun createAudioTrack(
        ordinal: Int = 0,
        codec: String = "aac",
        language: String = "English",
        channels: Int = 6,
        sampleRate: Int = 48000,
    ) = TrackInfo(
        kind = TrackKind.AUDIO,
        ordinal = ordinal,
        codec = codec,
        language = language,
        title = "",
        width = 0,
        height = 0,
        fpsMilli = 0,
        channels = channels,
        sampleRate = sampleRate,
        bitRate = 0L,
        isDefault = false,
    )

    private fun createSubtitleTrack(
        ordinal: Int = 0,
        codec: String = "subrip",
        language: String = "Portuguese",
    ) = TrackInfo(
        kind = TrackKind.SUBTITLE,
        ordinal = ordinal,
        codec = codec,
        language = language,
        title = "",
        width = 0,
        height = 0,
        fpsMilli = 0,
        channels = 0,
        sampleRate = 0,
        bitRate = 0L,
        isDefault = false,
    )

    @Test
    fun `formats full audio track with codec channels sample rate and language`() {
        val track =
            createAudioTrack(
                language = "English",
                codec = "aac",
                channels = 6,
                sampleRate = 48000,
            )
        val formatted = ContextRailView.formatAudioTrack(track)
        assertEquals("English (AAC · 6ch · 48 kHz)", formatted)
    }

    @Test
    fun `formats audio track without channels and sample rate`() {
        val track =
            createAudioTrack(
                language = "English",
                codec = "mp3",
                channels = 0,
                sampleRate = 0,
            )
        val formatted = ContextRailView.formatAudioTrack(track)
        assertEquals("English (MP3)", formatted)
    }

    @Test
    fun `formats audio track with channels only`() {
        val track =
            createAudioTrack(
                language = "Japanese",
                codec = "flac",
                channels = 2,
                sampleRate = 0,
            )
        val formatted = ContextRailView.formatAudioTrack(track)
        assertEquals("Japanese (FLAC · 2ch)", formatted)
    }

    @Test
    fun `formats audio track with blank language using fallback`() {
        val track =
            createAudioTrack(
                language = "",
                codec = "opus",
                channels = 2,
                sampleRate = 48000,
            )
        val defaultFormatted = ContextRailView.formatAudioTrack(track)
        assertEquals("Unknown language (OPUS · 2ch · 48 kHz)", defaultFormatted)

        val customFallback = ContextRailView.formatAudioTrack(track, unknownLanguage = "Desconhecido")
        assertEquals("Desconhecido (OPUS · 2ch · 48 kHz)", customFallback)
    }

    @Test
    fun `formats audio track with no technical details as language only`() {
        val track =
            createAudioTrack(
                language = "Spanish",
                codec = "",
                channels = 0,
                sampleRate = 0,
            )
        val formatted = ContextRailView.formatAudioTrack(track)
        assertEquals("Spanish", formatted)
    }

    @Test
    fun `formats subtitle track with codec and language`() {
        val track =
            createSubtitleTrack(
                language = "Portuguese",
                codec = "subrip",
            )
        val formatted = ContextRailView.formatSubtitleTrack(track)
        assertEquals("Portuguese (SUBRIP)", formatted)
    }

    @Test
    fun `formats subtitle track without codec as language only`() {
        val track =
            createSubtitleTrack(
                language = "Portuguese",
                codec = "",
            )
        val formatted = ContextRailView.formatSubtitleTrack(track)
        assertEquals("Portuguese", formatted)
    }

    @Test
    fun `formats subtitle track with blank language using fallback`() {
        val trackWithCodec =
            createSubtitleTrack(
                language = "",
                codec = "ass",
            )
        val formattedWithCodec = ContextRailView.formatSubtitleTrack(trackWithCodec)
        assertEquals("Unknown language (ASS)", formattedWithCodec)

        val trackWithoutCodec =
            createSubtitleTrack(
                language = "   ",
                codec = "",
            )
        val formattedWithoutCodec = ContextRailView.formatSubtitleTrack(trackWithoutCodec)
        assertEquals("Unknown language", formattedWithoutCodec)

        val customFallback =
            ContextRailView.formatSubtitleTrack(
                trackWithoutCodec,
                unknownLanguage = "Desconhecido",
            )
        assertEquals("Desconhecido", customFallback)
    }
}
