package com.tucavr.designsystem

import android.content.Context
import com.tucavr.R
import com.tucavr.filebrowser.TrackKind

/**
 * Parsed language, region and badge information for UI track rows.
 */
data class TrackDisplayInfo(
    val flagResId: Int,
    val titleText: String,
    val badgeText: String,
    val detailsText: String,
    val tag: String,
)

/**
 * Pure helper for resolving language, regional flags, track type badges,
 * and details formatting for audio and subtitle track rows (Issue #98).
 */
object LanguageRegionHelper {
    /**
     * Maps an ISO 639-1 or ISO 639-2 code to a display language name.
     */
    fun isoToLanguageName(code: String): String? {
        val cleaned = code.trim().lowercase()
        return when (cleaned) {
            "pt", "por" -> "Português"
            "en", "eng" -> "English"
            "es", "spa" -> "Español"
            "fr", "fra", "fre" -> "Français"
            "de", "deu", "ger" -> "Deutsch"
            "it", "ita" -> "Italiano"
            "ja", "jpn" -> "日本語"
            "ko", "kor" -> "한국어"
            "zh", "zho", "chi" -> "中文"
            "ru", "rus" -> "Русский"
            "ar", "ara" -> "العربية"
            "nl", "nld", "dut" -> "Nederlands"
            "pl", "pol" -> "Polski"
            "sv", "swe" -> "Svenska"
            "tr", "tur" -> "Türkçe"
            "hi", "hin" -> "हिन्दी"
            "uk", "ukr" -> "Українська"
            "cs", "ces", "cze" -> "Čeština"
            "da", "dan" -> "Dansk"
            "fi", "fin" -> "Suomi"
            "el", "ell", "gre" -> "Ελληνικά"
            "he", "heb" -> "עברית"
            "hu", "hun" -> "Magyar"
            "id", "ind" -> "Bahasa Indonesia"
            "no", "nor" -> "Norsk"
            "ro", "ron", "rum" -> "Română"
            "th", "tha" -> "ไทย"
            "vi", "vie" -> "Tiếng Việt"
            "ca", "cat" -> "Català"
            else -> null
        }
    }

    /**
     * Normalizes a language code (e.g., "por" -> "pt", "eng" -> "en").
     */
    fun normalizeLanguageCode(code: String): String {
        val primary = code.trim().split('-', '_').firstOrNull()?.lowercase() ?: ""
        return when (primary) {
            "por" -> "pt"
            "eng" -> "en"
            "spa" -> "es"
            "fra", "fre" -> "fr"
            "deu", "ger" -> "de"
            "ita" -> "it"
            "jpn" -> "ja"
            "kor" -> "ko"
            "zho", "chi" -> "zh"
            "rus" -> "ru"
            "ara" -> "ar"
            "nld", "dut" -> "nl"
            "pol" -> "pl"
            "swe" -> "sv"
            "tur" -> "tr"
            "hin" -> "hi"
            "ukr" -> "uk"
            "ces", "cze" -> "cs"
            "dan" -> "da"
            "fin" -> "fi"
            "ell", "gre" -> "el"
            "heb" -> "he"
            "hun" -> "hu"
            "ind" -> "id"
            "nor" -> "no"
            "ron", "rum" -> "ro"
            "tha" -> "th"
            "vie" -> "vi"
            "cat" -> "ca"
            else -> primary
        }
    }

    /**
     * Extracts region code and region display name from a tag (e.g. "pt-BR" -> "BR" / "Brasil").
     */
    fun extractRegionFromTag(tag: String): Pair<String, String>? {
        val parts = tag.trim().split('-', '_')
        if (parts.size < 2) return null

        val subtag = parts[1].uppercase()
        return when (subtag) {
            "BR" -> "BR" to "Brasil"
            "PT" -> "PT" to "Portugal"
            "US" -> "US" to "US"
            "GB", "UK" -> "GB" to "UK"
            "ES" -> "ES" to "España"
            "MX", "419" -> "MX" to "Latino"
            "FR" -> "FR" to "France"
            "DE" -> "DE" to "Deutschland"
            "JP" -> "JP" to "Japan"
            "IT" -> "IT" to "Italia"
            "KR" -> "KR" to "Korea"
            "CN" -> "CN" to "China"
            "TW" -> "TW" to "Taiwan"
            "HK" -> "HK" to "Hong Kong"
            "RU" -> "RU" to "Russia"
            "CA" -> "CA" to "Canada"
            "AU" -> "AU" to "Australia"
            "AR" -> "AR" to "Argentina"
            "CL" -> "CL" to "Chile"
            "CO" -> "CO" to "Colombia"
            else -> null
        }
    }

    /**
     * Extracts region code and region display name from a track title.
     */
    fun extractRegionFromTitle(title: String): Pair<String, String>? {
        val lower = title.lowercase()
        return when {
            lower.contains("português (brasil)") ||
                lower.contains("portuguese (brazil)") ||
                lower.contains("brasileiro") ||
                lower.contains("brazilian") ||
                lower.contains("pt-br") ||
                lower.contains("pt_br") ||
                lower.contains("brasil") ||
                lower.contains("brazil") -> "BR" to "Brasil"

            lower.contains("português (portugal)") ||
                lower.contains("portuguese (portugal)") ||
                lower.contains("europeu") ||
                lower.contains("european") ||
                lower.contains("pt-pt") ||
                lower.contains("pt_pt") ||
                lower.contains("portugal") -> "PT" to "Portugal"

            lower.contains("latino") ||
                lower.contains("latin") ||
                lower.contains("latinoamérica") ||
                lower.contains("amércia latina") ||
                lower.contains("mexico") ||
                lower.contains("méxico") ||
                lower.contains("es-mx") ||
                lower.contains("es-419") -> "MX" to "Latino"

            lower.contains("castellano") ||
                lower.contains("castilian") ||
                lower.contains("españa") ||
                lower.contains("spain") ||
                lower.contains("es-es") -> "ES" to "España"

            lower.contains("english (us)") ||
                lower.contains("en-us") ||
                lower.contains("united states") ||
                lower.contains("usa") ||
                lower.contains("american") -> "US" to "US"

            lower.contains("english (uk)") ||
                lower.contains("english (gb)") ||
                lower.contains("en-gb") ||
                lower.contains("united kingdom") ||
                lower.contains("great britain") ||
                lower.contains("british") -> "GB" to "UK"

            lower.contains("québécois") ||
                lower.contains("quebec") ||
                lower.contains("canada") ||
                lower.contains("fr-ca") -> "CA" to "Canada"

            lower.contains("français (france)") ||
                lower.contains("france") ||
                lower.contains("fr-fr") -> "FR" to "France"

            lower.contains("germany") ||
                lower.contains("deutschland") ||
                lower.contains("de-de") -> "DE" to "Deutschland"

            lower.contains("japan") ||
                lower.contains("nihon") ||
                lower.contains("ja-jp") -> "JP" to "Japan"

            else -> null
        }
    }

    /**
     * Default per-language region.
     */
    fun defaultRegionForLanguage(langCode: String): Pair<String, String>? {
        return when (langCode) {
            "pt" -> "BR" to "Brasil"
            "es" -> "ES" to "España"
            "en" -> "US" to "US"
            "fr" -> "FR" to "France"
            "de" -> "DE" to "Deutschland"
            "it" -> "IT" to "Italia"
            "ja" -> "JP" to "Japan"
            "ko" -> "KR" to "Korea"
            "zh" -> "CN" to "China"
            "ru" -> "RU" to "Russia"
            else -> null
        }
    }

    /**
     * Resolves the country flag drawable resource ID.
     */
    fun resolveFlagDrawable(regionCode: String?): Int {
        return when (regionCode?.uppercase()) {
            "BR" -> R.drawable.flag_br
            "PT" -> R.drawable.flag_pt
            "US" -> R.drawable.flag_us
            "GB", "UK" -> R.drawable.flag_gb
            "ES" -> R.drawable.flag_es
            "MX" -> R.drawable.ic_flag_mx
            "FR" -> R.drawable.ic_flag_fr
            "DE" -> R.drawable.ic_flag_de
            "JP" -> R.drawable.flag_jp
            else -> R.drawable.flag_und
        }
    }

    /**
     * Resolves complete display info for a track row.
     */
    fun resolveTrackDisplayInfo(
        context: Context,
        language: String,
        title: String,
        ordinal: Int,
        kind: TrackKind,
        channels: Int = 0,
        codec: String = "",
        isExternal: Boolean = false,
    ): TrackDisplayInfo {
        val tagTrimmed = language.trim()
        val normLang = normalizeLanguageCode(tagTrimmed)

        val (effectiveLang, baseLangName) =
            if (normLang.isNotEmpty()) {
                val name =
                    isoToLanguageName(tagTrimmed)
                        ?: isoToLanguageName(normLang)
                        ?: tagTrimmed
                normLang to name
            } else {
                val titleLower = title.lowercase()
                when {
                    titleLower.contains("português") || titleLower.contains("portuguese") -> "pt" to "Português"
                    titleLower.contains("english") || titleLower.contains("inglês") -> "en" to "English"
                    titleLower.contains("español") || titleLower.contains("spanish") ||
                        titleLower.contains("latino") || titleLower.contains("castellano") -> "es" to "Español"
                    titleLower.contains("français") || titleLower.contains("french") -> "fr" to "Français"
                    titleLower.contains("deutsch") || titleLower.contains("german") -> "de" to "Deutsch"
                    titleLower.contains("japanese") || titleLower.contains("japonês") -> "ja" to "日本語"
                    else -> "" to ""
                }
            }

        val regionPair =
            extractRegionFromTag(tagTrimmed)
                ?: extractRegionFromTitle(title)
                ?: if (effectiveLang.isNotEmpty()) defaultRegionForLanguage(effectiveLang) else null

        val regionCode = regionPair?.first
        val regionName = regionPair?.second

        val flagResId = resolveFlagDrawable(regionCode)

        val titleText =
            when {
                effectiveLang.isEmpty() -> {
                    if (title.isNotBlank() && !title.startsWith("Track ", ignoreCase = true)) {
                        title
                    } else {
                        context.getString(R.string.track_unknown_language_format, ordinal + 1)
                    }
                }
                regionName != null -> "$baseLangName ($regionName)"
                else -> baseLangName
            }

        val badgeText =
            when (kind) {
                TrackKind.AUDIO -> {
                    val titleLower = title.lowercase()
                    when {
                        titleLower.contains("commentary") || titleLower.contains("comentário") ->
                            context.getString(R.string.audio_track_badge_commentary)
                        channels >= 8 ->
                            context.getString(R.string.audio_track_badge_surround_71)
                        channels >= 6 ->
                            context.getString(R.string.audio_track_badge_surround_51)
                        channels == 2 ->
                            context.getString(R.string.audio_track_badge_stereo)
                        channels == 1 ->
                            context.getString(R.string.audio_track_badge_mono)
                        channels > 2 ->
                            context.getString(R.string.audio_track_badge_surround)
                        else -> ""
                    }
                }
                TrackKind.SUBTITLE -> {
                    if (isExternal) {
                        context.getString(R.string.subtitles_badge_external)
                    } else {
                        context.getString(R.string.subtitles_badge_embedded)
                    }
                }
                TrackKind.VIDEO -> ""
            }

        val tagDisplay =
            when {
                effectiveLang.isNotEmpty() && regionCode != null -> "${effectiveLang.uppercase()}-$regionCode"
                effectiveLang.isNotEmpty() -> effectiveLang.uppercase()
                tagTrimmed.isNotEmpty() -> tagTrimmed.uppercase()
                else -> ""
            }

        val detailsParts = mutableListOf<String>()
        if (tagDisplay.isNotEmpty()) {
            detailsParts.add(tagDisplay)
        }
        if (badgeText.isNotEmpty()) {
            detailsParts.add(badgeText)
        }
        if (codec.isNotBlank()) {
            detailsParts.add(codec.uppercase())
        }

        val detailsText =
            if (detailsParts.isNotEmpty()) {
                detailsParts.joinToString(" · ")
            } else {
                ""
            }

        return TrackDisplayInfo(
            flagResId = flagResId,
            titleText = titleText,
            badgeText = badgeText,
            detailsText = detailsText,
            tag = tagDisplay,
        )
    }
}
