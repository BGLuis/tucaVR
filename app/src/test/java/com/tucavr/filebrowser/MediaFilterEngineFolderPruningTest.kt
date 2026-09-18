package com.tucavr.filebrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilterEngineFolderPruningTest {

    private val folderEntry = MediaEntry("Filmes 3D", "/path/Filmes 3D", 0, 1000L, MediaType.DIRECTORY)

    @Test
    fun keepsFolderWhenMatches3DFormatFilter() {
        val summaryWith360 = FolderSummary(
            totalItems = 5,
            videoCount = 3,
            audioCount = 0,
            imageCount = 0,
            previewEntries = listOf(
                MediaEntry("skydiving_360.mp4", "/path/Filmes 3D/skydiving_360.mp4", 100, 100, MediaType.VIDEO, format3DHint = Format3DType.VR_360)
            ),
            available3DFormats = setOf(Format3DType.VR_360),
            hasPlayableMediaWithinDepth = true
        )

        // Filtro 360° ativo: a pasta CONTÉM vídeos 360°, logo deve ser MANTIDA
        assertTrue(
            MediaFilterEngine.matchesFolderFilter(
                entry = folderEntry,
                summary = summaryWith360,
                query = "",
                format3DFilter = Format3DFilter.VR_360
            )
        )
    }

    @Test
    fun prunesFolderWhenLacksTarget3DFormat() {
        val summaryOnlySbs = FolderSummary(
            totalItems = 5,
            videoCount = 2,
            audioCount = 0,
            imageCount = 0,
            previewEntries = listOf(
                MediaEntry("movie_sbs.mp4", "/path/Filmes 3D/movie_sbs.mp4", 100, 100, MediaType.VIDEO, format3DHint = Format3DType.SBS)
            ),
            available3DFormats = setOf(Format3DType.SBS),
            hasPlayableMediaWithinDepth = true
        )

        // Filtro 360° ativo: a pasta NÃO contém vídeos 360° (só SBS), logo deve ser OCULTADA
        assertFalse(
            MediaFilterEngine.matchesFolderFilter(
                entry = folderEntry,
                summary = summaryOnlySbs,
                query = "",
                format3DFilter = Format3DFilter.VR_360
            )
        )
    }

    @Test
    fun prunesEmptyFoldersRegardlessOfFilter() {
        val emptySummary = FolderSummary(
            totalItems = 0,
            videoCount = 0,
            audioCount = 0,
            imageCount = 0,
            previewEntries = emptyList(),
            available3DFormats = emptySet(),
            hasPlayableMediaWithinDepth = false
        )

        // Sem NENHUM filtro ativo: pasta sem mídia reproduzível (nem em subpastas) deve
        // ser OCULTADA sempre, não só quando o usuário liga um filtro -- é o comportamento
        // pedido pra não poluir a listagem com pastas vazias por padrão.
        assertFalse(
            MediaFilterEngine.matchesFolderFilter(
                entry = folderEntry,
                summary = emptySummary,
                query = ""
            )
        )

        // Filtro de vídeo ativo: pasta vazia (0 itens) deve ser OCULTADA
        assertFalse(
            MediaFilterEngine.matchesFolderFilter(
                entry = folderEntry,
                summary = emptySummary,
                query = "",
                typeFilter = MediaTypeFilter.VIDEO
            )
        )

        // Filtro 3D SBS ativo: pasta vazia deve ser OCULTADA
        assertFalse(
            MediaFilterEngine.matchesFolderFilter(
                entry = folderEntry,
                summary = emptySummary,
                query = "",
                format3DFilter = Format3DFilter.SBS
            )
        )
    }

    @Test
    fun keepsFolderWithNoDirectItemsButMediaFoundWithinSubfolders() {
        // totalItems=0 no nível imediato (ex.: só contém subpastas) mas o probe de 2 níveis
        // achou mídia dentro de uma subpasta -- a pasta deve ser MANTIDA, não podada.
        val onlySubfoldersSummary = FolderSummary(
            totalItems = 0,
            videoCount = 0,
            audioCount = 0,
            imageCount = 0,
            previewEntries = emptyList(),
            available3DFormats = emptySet(),
            hasPlayableMediaWithinDepth = true
        )

        assertTrue(
            MediaFilterEngine.matchesFolderFilter(
                entry = folderEntry,
                summary = onlySubfoldersSummary,
                query = ""
            )
        )
    }

    @Test
    fun prunesFolderWhenItHasNoVideosUnderVideoFilter() {
        val photosOnlySummary = FolderSummary(
            totalItems = 10,
            videoCount = 0,
            audioCount = 2,
            imageCount = 8,
            previewEntries = emptyList(),
            available3DFormats = emptySet(),
            hasPlayableMediaWithinDepth = true
        )

        // Filtro de Vídeos ativo: pasta só tem fotos/músicas, deve ser OCULTADA
        assertFalse(
            MediaFilterEngine.matchesFolderFilter(
                entry = folderEntry,
                summary = photosOnlySummary,
                query = "",
                typeFilter = MediaTypeFilter.VIDEO
            )
        )

        // Filtro de Áudios ativo: pasta tem 2 áudios, deve ser MANTIDA
        assertTrue(
            MediaFilterEngine.matchesFolderFilter(
                entry = folderEntry,
                summary = photosOnlySummary,
                query = "",
                typeFilter = MediaTypeFilter.AUDIO
            )
        )
    }

    @Test
    fun keepsFolderWhenNoFiltersAreActiveAndHasItems() {
        val summary = FolderSummary(
            totalItems = 3,
            videoCount = 1,
            audioCount = 1,
            imageCount = 1,
            previewEntries = emptyList(),
            available3DFormats = setOf(Format3DType.FLAT_2D),
            hasPlayableMediaWithinDepth = true
        )

        assertTrue(
            MediaFilterEngine.matchesFolderFilter(
                entry = folderEntry,
                summary = summary,
                query = "",
                typeFilter = MediaTypeFilter.ALL,
                format3DFilter = Format3DFilter.ALL
            )
        )
    }
}
