package com.tucavr.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// T13.1: parse da wire produzida por rust/media-logic/src/metadata_wire.rs::encode
// -- a mesma gramatica testada do lado Rust (encode_full_media_info_matches_expected_wire),
// aqui do lado que realmente consome (decode nao existe em Rust de proposito, ver
// doc-comment de metadata_wire.rs). Runtime Rust nunca roda no host (rust/core exige
// NDK), entao este e o unico teste automatizado que exercita o formato de ponta a ponta.
class MediaMetadataReaderParseTest {
    @Test
    fun parsesFullWireWithTagsAndTracks() {
        val wire =
            "F\tmatroska\tMatroska / WebM\t7384000000\t25000000\t7\t0\n" +
                "M\ttitle\tBig Buck Bunny\n" +
                "T\tvideo\t0\thevc\t\t\t3840\t2160\t60000\t0\t0\t22000000\t1\n" +
                "T\taudio\t1\teac3\tpor\t\t0\t0\t0\t6\t48000\t448000\t0"

        val meta = MediaMetadataReader.parse(wire)!!

        assertEquals("matroska", meta.container)
        assertEquals("Matroska / WebM", meta.containerLong)
        assertEquals(7_384_000L, meta.durationMs) // us -> ms
        assertEquals(25_000_000L, meta.bitRate)
        assertEquals(7, meta.format3dIndex)
        assertEquals(0, meta.detectionConfidence)
        assertEquals(listOf("title" to "Big Buck Bunny"), meta.tags)

        assertEquals(1, meta.videoTracks.size)
        val video = meta.videoTracks[0]
        assertEquals("hevc", video.codec)
        assertEquals(3840, video.width)
        assertEquals(2160, video.height)
        assertEquals(60_000, video.fpsMilli)
        assertTrue(video.isDefault)

        assertEquals(1, meta.audioTracks.size)
        val audio = meta.audioTracks[0]
        assertEquals("eac3", audio.codec)
        assertEquals("por", audio.language)
        assertEquals(6, audio.channels)
        assertEquals(48_000, audio.sampleRate)
        assertEquals(false, audio.isDefault)

        assertEquals(0, meta.subtitleTracks.size)
    }

    @Test
    fun parsesFormatLineOnlyWithNoTagsOrTracks() {
        val meta = MediaMetadataReader.parse("F\tmp4\tMP4\t0\t0\t1\t1")!!

        assertEquals("mp4", meta.container)
        assertEquals(0L, meta.durationMs)
        assertEquals(1, meta.format3dIndex)
        assertEquals(1, meta.detectionConfidence)
        assertTrue(meta.tags.isEmpty())
        assertTrue(meta.tracks.isEmpty())
    }

    @Test
    fun parsesLegacyFormatLineWithoutFormat3dFields() {
        val meta = MediaMetadataReader.parse("F\tmp4\tMP4\t0\t0")!!

        assertEquals("mp4", meta.container)
        assertEquals(0L, meta.durationMs)
        assertEquals(0, meta.format3dIndex)
        assertEquals(4, meta.detectionConfidence)
        assertTrue(meta.tags.isEmpty())
        assertTrue(meta.tracks.isEmpty())
    }

    @Test
    fun errorPrefixReturnsNull() {
        assertNull(MediaMetadataReader.parse("ERROR:arquivo nao encontrado"))
    }

    @Test
    fun unknownRecordTypeIsIgnoredWithoutBreakingTheRest() {
        val wire =
            "F\tmp4\tMP4\t1000000\t500000\n" +
                "X\tsomething\tfrom\tthe\tfuture\n" +
                "T\tvideo\t0\th264\t\t\t1920\t1080\t30000\t0\t0\t500000\t1"

        val meta = MediaMetadataReader.parse(wire)!!

        assertEquals(1, meta.tracks.size)
        assertEquals("h264", meta.tracks[0].codec)
    }

    @Test
    fun subtitleTrackWithUnknownLanguageParsesWithEmptyFields() {
        val wire =
            "F\tmatroska\tMatroska\t1000000\t0\n" +
                "T\tsubtitle\t0\tsubrip\t\t\t0\t0\t0\t0\t0\t0\t0"

        val meta = MediaMetadataReader.parse(wire)!!

        assertEquals(1, meta.subtitleTracks.size)
        assertEquals("subrip", meta.subtitleTracks[0].codec)
        assertEquals("", meta.subtitleTracks[0].language)
    }

    @Test
    fun roundTripSerializationCacheEntry() {
        val tracks =
            listOf(
                TrackInfo(
                    kind = TrackKind.VIDEO,
                    ordinal = 0,
                    codec = "hevc",
                    language = "",
                    title = "Video",
                    width = 3840,
                    height = 2160,
                    fpsMilli = 60000,
                    channels = 0,
                    sampleRate = 0,
                    bitRate = 20000000L,
                    isDefault = true,
                ),
                TrackInfo(
                    kind = TrackKind.AUDIO,
                    ordinal = 1,
                    codec = "aac",
                    language = "por",
                    title = "Portuguese",
                    width = 0,
                    height = 0,
                    fpsMilli = 0,
                    channels = 6,
                    sampleRate = 48000,
                    bitRate = 384000L,
                    isDefault = false,
                ),
                TrackInfo(
                    kind = TrackKind.SUBTITLE,
                    ordinal = 2,
                    codec = "subrip",
                    language = "eng",
                    title = "English Sub",
                    width = 0,
                    height = 0,
                    fpsMilli = 0,
                    channels = 0,
                    sampleRate = 0,
                    bitRate = 0L,
                    isDefault = false,
                ),
            )
        val tags = listOf("title" to "Test Title", "artist" to "Test Artist")

        val tracksJson = serializeTracks(tracks)
        val tagsJson = serializeTags(tags)

        val deserializedTracks = deserializeTracks(tracksJson)
        val deserializedTags = deserializeTags(tagsJson)

        assertEquals(tracks, deserializedTracks)
        assertEquals(tags, deserializedTags)

        val entry =
            MediaMetadataCacheEntry(
                mediaKey = "test_key",
                container = "mkv",
                containerLong = "Matroska",
                durationMs = 120000L,
                bitRate = 25000000L,
                format3dIndex = 2,
                detectionConfidence = 3,
                videoWidth = 3840,
                videoHeight = 2160,
                videoCodec = "hevc",
                fetchedAt = 1000L,
                tracksJson = tracksJson,
                tagsJson = tagsJson,
                fileSizeBytes = 500000000L,
            )

        val meta = entry.toMediaMetadata()
        assertEquals("mkv", meta.container)
        assertEquals("Matroska", meta.containerLong)
        assertEquals(120000L, meta.durationMs)
        assertEquals(25000000L, meta.bitRate)
        assertEquals(2, meta.format3dIndex)
        assertEquals(3, meta.detectionConfidence)
        assertEquals(tags, meta.tags)
        assertEquals(tracks, meta.tracks)
        assertEquals(1, meta.videoTracks.size)
        assertEquals(1, meta.audioTracks.size)
        assertEquals(1, meta.subtitleTracks.size)
    }

    @Test
    fun parsesSubtitleWireAndAudioWire() {
        val subWire = "por\tPortuguês\t0\neng\tEnglish\t1"
        val subItems = com.tucavr.designsystem.parseSubtitleWire(subWire)
        assertEquals(2, subItems.size)
        assertEquals(0, subItems[0].index)
        assertEquals("por", subItems[0].language)
        assertEquals("Português", subItems[0].title)
        assertEquals(false, subItems[0].isExternal)
        assertEquals(1, subItems[1].index)
        assertEquals("eng", subItems[1].language)
        assertEquals("English", subItems[1].title)
        assertEquals(true, subItems[1].isExternal)

        val audioWire = "0\taac\tpor\tDublado\t6\t48000\n1\tac3\teng\tOriginal\t2\t44100"
        val audioTracks = com.tucavr.designsystem.parseAudioWire(audioWire)
        assertEquals(2, audioTracks.size)
        assertEquals(0, audioTracks[0].ordinal)
        assertEquals("aac", audioTracks[0].codec)
        assertEquals("por", audioTracks[0].language)
        assertEquals("Dublado", audioTracks[0].title)
        assertEquals(6, audioTracks[0].channels)
        assertEquals(48000, audioTracks[0].sampleRate)
        assertEquals(TrackKind.AUDIO, audioTracks[0].kind)

        assertEquals(1, audioTracks[1].ordinal)
        assertEquals("ac3", audioTracks[1].codec)
        assertEquals("eng", audioTracks[1].language)
        assertEquals("Original", audioTracks[1].title)
        assertEquals(2, audioTracks[1].channels)
        assertEquals(44100, audioTracks[1].sampleRate)
    }
}
