package com.tucavr.filebrowser

import java.util.Locale

enum class MediaTypeFilter {
    ALL,
    VIDEO,
    AUDIO,
    IMAGE
}

enum class Format3DFilter {
    ALL,
    FLAT_2D,
    SBS,
    OU,
    VR_180,
    VR_360
}

enum class DateFilter {
    ALL,
    RECENT_24H,
    LAST_7_DAYS,
    LAST_30_DAYS
}

/**
 * Motor de filtragem, busca e heurística de formato 3D da biblioteca de mídia (T12.1, T12.2).
 */
object MediaFilterEngine {

    private const val MS_IN_24H = 24L * 60 * 60 * 1000
    private const val MS_IN_7_DAYS = 7L * MS_IN_24H
    private const val MS_IN_30_DAYS = 30L * MS_IN_24H

    /**
     * Detecta o formato 3D provável a partir do nome do arquivo (heurística leve e instantânea).
     */
    fun detectFormat3DFromFilename(filename: String): Format3DType {
        val lower = filename.lowercase(Locale.ROOT)

        // 360 VR (SBS, OU ou Mono)
        if (lower.contains("360_sbs") || lower.contains("360.sbs") || lower.contains("360sbs") ||
            lower.contains("360_ou") || lower.contains("360.ou") || lower.contains("360ou") ||
            lower.contains(".360.") || lower.contains("_360_") || lower.contains(" 360 ") ||
            lower.contains("_360.") || lower.contains("-360.") || lower.contains(".360_") ||
            lower.contains("equirectangular") || lower.contains("vr360") || lower.contains("360vr")
        ) {
            return Format3DType.VR_360
        }

        // 180 VR (SBS ou Mono)
        if (lower.contains("180_sbs") || lower.contains("180.sbs") || lower.contains("180sbs") ||
            lower.contains("vr180") || lower.contains("180vr") || lower.contains(".180.") ||
            lower.contains("_180_") || lower.contains(" 180 ") || lower.contains("_180.") ||
            lower.contains("-180.") || lower.contains("half-equirectangular")
        ) {
            return Format3DType.VR_180
        }

        // Over-Under (Top-Bottom)
        if (lower.contains("overunder") || lower.contains("over-under") || lower.contains("topbottom") ||
            lower.contains("top-bottom") || lower.contains(".ou.") || lower.contains("_ou_") ||
            lower.contains("_ou.") || lower.contains("-ou.") || lower.contains(".hou.") ||
            lower.contains("half-ou") || lower.contains("3dv") || lower.contains("3d-ou") ||
            lower.contains("3d.ou")
        ) {
            return Format3DType.OU
        }

        // Side-by-Side (SBS)
        if (lower.contains(".sbs.") || lower.contains("_sbs_") || lower.contains("-sbs") ||
            lower.contains("_sbs.") || lower.contains(".sbs") || lower.contains("half-sbs") ||
            lower.contains(".hsbs.") || lower.contains("_hsbs_") || lower.contains(" 3d sbs") ||
            lower.contains("3d-sbs") || lower.contains("3d.sbs") || lower.contains("3dh") ||
            lower.contains("sidebyside") || lower.contains("side-by-side")
        ) {
            return Format3DType.SBS
        }

        return Format3DType.FLAT_2D
    }

    /**
     * Avalia se uma [MediaEntry] atende a todos os critérios de filtro ativos.
     * Suporta inspeção inteligente de pastas ([folderSummary]) para ocultar pastas vazias
     * ou pastas sem mídias correspondentes aos filtros selecionados (ex: filtros 3D ou tipo).
     */
    fun matchesFilter(
        entry: MediaEntry,
        query: String,
        typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
        format3DFilter: Format3DFilter = Format3DFilter.ALL,
        dateFilter: DateFilter = DateFilter.ALL,
        nowMs: Long = System.currentTimeMillis(),
        folderSummary: FolderSummary? = null
    ): Boolean {
        // Se for diretório, aplica regras de filtragem e poda inteligente de pastas
        if (entry.type == MediaType.DIRECTORY) {
            return matchesFolderFilter(
                entry = entry,
                summary = folderSummary,
                query = query,
                typeFilter = typeFilter,
                format3DFilter = format3DFilter,
                dateFilter = dateFilter
            )
        }

        // 1. Busca por texto (T12.1)
        if (query.isNotBlank() && !matchesSearch(entry.name, query)) {
            return false
        }

        // 2. Filtro por tipo de mídia (T12.2)
        when (typeFilter) {
            MediaTypeFilter.ALL -> {}
            MediaTypeFilter.VIDEO -> if (entry.type != MediaType.VIDEO) return false
            MediaTypeFilter.AUDIO -> if (entry.type != MediaType.AUDIO) return false
            MediaTypeFilter.IMAGE -> if (entry.type != MediaType.IMAGE) return false
        }

        // 3. Filtro por formato 3D (T12.2)
        when (format3DFilter) {
            Format3DFilter.ALL -> {}
            Format3DFilter.FLAT_2D -> if (entry.format3DHint != Format3DType.FLAT_2D) return false
            Format3DFilter.SBS -> if (entry.format3DHint != Format3DType.SBS) return false
            Format3DFilter.OU -> if (entry.format3DHint != Format3DType.OU) return false
            Format3DFilter.VR_180 -> if (entry.format3DHint != Format3DType.VR_180) return false
            Format3DFilter.VR_360 -> if (entry.format3DHint != Format3DType.VR_360) return false
        }

        // 4. Filtro por data de modificação (T12.2)
        if (dateFilter != DateFilter.ALL) {
            if (entry.lastModified <= 0L) return false
            val ageMs = (nowMs - entry.lastModified).coerceAtLeast(0L)
            when (dateFilter) {
                DateFilter.ALL -> {}
                DateFilter.RECENT_24H -> if (ageMs > MS_IN_24H) return false
                DateFilter.LAST_7_DAYS -> if (ageMs > MS_IN_7_DAYS) return false
                DateFilter.LAST_30_DAYS -> if (ageMs > MS_IN_30_DAYS) return false
            }
        }

        return true
    }

    /**
     * Avalia se uma pasta deve permanecer visível com base no seu conteúdo e nos filtros ativos.
     */
    fun matchesFolderFilter(
        entry: MediaEntry,
        summary: FolderSummary?,
        query: String,
        typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
        format3DFilter: Format3DFilter = Format3DFilter.ALL,
        dateFilter: DateFilter = DateFilter.ALL
    ): Boolean {
        // Se a busca estiver ativa e o nome da pasta não corresponder
        if (query.isNotBlank()) {
            val nameMatch = matchesSearch(entry.name, query)
            val childrenMatch = summary?.previewEntries?.any { matchesSearch(it.name, query) } == true
            if (!nameMatch && !childrenMatch) return false
        }

        if (summary == null) {
            // Sem resumo conhecido: permite se não houver filtros restritivos
            return true
        }

        val hasActiveFilter = query.isNotBlank() || typeFilter != MediaTypeFilter.ALL || format3DFilter != Format3DFilter.ALL || dateFilter != DateFilter.ALL

        // Poda de pastas vazias se houver filtros ativos
        if (summary.totalItems == 0 && hasActiveFilter) {
            return false
        }

        // Filtro por Tipo de Mídia aplicado a pastas
        when (typeFilter) {
            MediaTypeFilter.ALL -> {}
            MediaTypeFilter.VIDEO -> if (summary.videoCount == 0) return false
            MediaTypeFilter.AUDIO -> if (summary.audioCount == 0) return false
            MediaTypeFilter.IMAGE -> if (summary.imageCount == 0) return false
        }

        // Filtro por Formato 3D aplicado a pastas
        if (format3DFilter != Format3DFilter.ALL) {
            val targetFormat = when (format3DFilter) {
                Format3DFilter.FLAT_2D -> Format3DType.FLAT_2D
                Format3DFilter.SBS -> Format3DType.SBS
                Format3DFilter.OU -> Format3DType.OU
                Format3DFilter.VR_180 -> Format3DType.VR_180
                Format3DFilter.VR_360 -> Format3DType.VR_360
                Format3DFilter.ALL -> null
            }
            if (targetFormat != null && !summary.available3DFormats.contains(targetFormat)) {
                return false
            }
        }

        return true
    }

    /**
     * Verifica se o nome do arquivo dá match na query de busca (substring ou todos os tokens presentes).
     */
    fun matchesSearch(targetName: String, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true

        val lowerTarget = targetName.lowercase(Locale.ROOT)
        val tokens = trimmed.lowercase(Locale.ROOT).split("\\s+".toRegex()).filter { it.isNotEmpty() }

        if (tokens.isEmpty()) return true

        // Se todos os tokens da busca estão presentes em qualquer ordem
        return tokens.all { token ->
            lowerTarget.contains(token) || fuzzyMatch(lowerTarget, token)
        }
    }

    /**
     * Fuzzy matching tolerante a 1 erro de digitação para termos com mais de 3 caracteres.
     */
    private fun fuzzyMatch(text: String, token: String): Boolean {
        if (token.length <= 3) return text.contains(token)

        // Subsequência de caracteres ordenada
        var tokenIdx = 0
        for (ch in text) {
            if (ch == token[tokenIdx]) {
                tokenIdx++
                if (tokenIdx == token.length) return true
            }
        }
        return false
    }

    /**
     * Encontra os intervalos de índice para destacar (*highlight*) visualmente na UI.
     */
    fun findHighlightRanges(text: String, query: String): List<IntRange> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val lowerText = text.lowercase(Locale.ROOT)
        val tokens = trimmed.lowercase(Locale.ROOT).split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val ranges = mutableListOf<IntRange>()

        for (token in tokens) {
            var startIndex = 0
            while (startIndex < lowerText.length) {
                val found = lowerText.indexOf(token, startIndex)
                if (found == -1) break
                ranges.add(found until (found + token.length))
                startIndex = found + token.length
            }
        }

        return ranges
    }
}
