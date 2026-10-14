package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guarda automatizada de paridade de chaves e formatação de internacionalização (i18n).
 *
 * Valida os requisitos de Fase 0.3 Seção 10:
 * - Paridade estrita de chaves entre `values/` (EN), `values-pt-rBR/` (PT-BR) e `values-es/` (ES).
 * - Preservação da ordem de declaração de chaves para facilitar diffs lado a lado.
 * - Paridade de placeholders posicionais (%1$s, %1$d, etc.) entre o padrão e as traduções.
 * - Proibição rigorosa de placeholders não-posicionais (%s, %d sem $), prevenindo quebras em runtime.
 * - Formas de plural completas ("one" e "other") para todos os <plurals>.
 */
class I18nParityTest {
    data class StringEntry(
        val name: String,
        val isPlural: Boolean,
        // "default" -> texto para <string>; quantity -> texto para <plurals>
        val texts: Map<String, String>,
    )

    data class LocaleResources(
        val locale: String,
        val file: File,
        val entries: List<StringEntry>,
    ) {
        val parentDirName: String = file.parentFile?.name ?: file.name
        val keys: List<String> = entries.map { it.name }
        val keySet: Set<String> = keys.toSet()
        val entryMap: Map<String, StringEntry> = entries.associateBy { it.name }
    }

    companion object {
        private lateinit var defaultRes: LocaleResources
        private lateinit var translationLocales: List<LocaleResources>
        private lateinit var allLocales: List<LocaleResources>

        private val POSITIONAL_PLACEHOLDER_REGEX = Regex("%[0-9]+\\$[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]")
        private val NON_POSITIONAL_PLACEHOLDER_REGEX = Regex("%(?![0-9]+\\$)[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]")

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val resDir = resolveResDirectory()
            defaultRes = parseStringsXml("en (default)", File(resDir, "values/strings.xml"))

            val candidateDirs =
                resDir.listFiles { dir ->
                    dir.isDirectory && dir.name.startsWith("values-") && File(dir, "strings.xml").isFile
                }?.sortedBy { it.name } ?: emptyList()

            translationLocales =
                candidateDirs.map { dir ->
                    val localeTag = dir.name.removePrefix("values-")
                    parseStringsXml(localeTag, File(dir, "strings.xml"))
                }
            assertTrue("Pelo menos uma tradução deve existir em ${resDir.absolutePath}", translationLocales.isNotEmpty())
            allLocales = listOf(defaultRes) + translationLocales
        }

        private fun resolveResDirectory(): File {
            val userDir = File(System.getProperty("user.dir") ?: ".")
            val candidateApp = File(userDir, "src/main/res")
            if (candidateApp.isDirectory) return candidateApp
            val candidateRoot = File(userDir, "app/src/main/res")
            if (candidateRoot.isDirectory) return candidateRoot
            error("Diretório de resources não encontrado a partir de: ${userDir.absolutePath}")
        }

        private fun parseStringsXml(
            locale: String,
            file: File,
        ): LocaleResources {
            assertTrue("Arquivo de strings não encontrado: ${file.absolutePath}", file.isFile)

            val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val doc = docBuilder.parse(file)
            val root = doc.documentElement

            val entries = mutableListOf<StringEntry>()
            val nodeList = root.childNodes

            for (i in 0 until nodeList.length) {
                val node = nodeList.item(i)
                if (node.nodeType != Node.ELEMENT_NODE) continue
                val elem = node as Element

                when (elem.tagName) {
                    "string" -> {
                        val name = elem.getAttribute("name")
                        val text = elem.textContent ?: ""
                        entries.add(StringEntry(name, isPlural = false, texts = mapOf("default" to text)))
                    }
                    "plurals" -> {
                        val name = elem.getAttribute("name")
                        val items = mutableMapOf<String, String>()
                        val itemNodes = elem.getElementsByTagName("item")
                        for (j in 0 until itemNodes.length) {
                            val itemElem = itemNodes.item(j) as Element
                            val quantity = itemElem.getAttribute("quantity")
                            items[quantity] = itemElem.textContent ?: ""
                        }
                        entries.add(StringEntry(name, isPlural = true, texts = items))
                    }
                }
            }

            return LocaleResources(locale, file, entries)
        }
    }

    @Test
    fun `all locales have the exact same set of keys`() {
        val defaultKeys = defaultRes.keySet

        for (trans in translationLocales) {
            val transKeys = trans.keySet
            val missing = defaultKeys - transKeys
            val extra = transKeys - defaultKeys
            assertTrue(
                "Discrepância de chaves em ${trans.parentDirName}/strings.xml (${trans.locale}): faltantes=$missing, extras=$extra",
                missing.isEmpty() && extra.isEmpty(),
            )
            assertEquals(
                "Total de chaves em ${trans.parentDirName}/strings.xml (${trans.locale}) deve ser idêntico ao default",
                defaultKeys.size,
                transKeys.size,
            )
        }
    }

    @Test
    fun `key declaration order strictly mirrors default values strings_xml`() {
        for (trans in translationLocales) {
            assertEquals(
                "A ordem das chaves em ${trans.parentDirName}/strings.xml (${trans.locale}) deve espelhar exatamente values/strings.xml",
                defaultRes.keys,
                trans.keys,
            )
        }
    }

    @Test
    fun `no non-positional placeholders are used in any locale`() {
        for (loc in allLocales) {
            for (entry in loc.entries) {
                for ((subKey, text) in entry.texts) {
                    val cleanText = text.replace("%%", "")
                    val nonPositional = NON_POSITIONAL_PLACEHOLDER_REGEX.findAll(cleanText).map { it.value }.toList()
                    assertTrue(
                        "Placeholder não-posicional encontrado em ${loc.parentDirName}/${loc.file.name} " +
                            "(${loc.locale}) na chave '${entry.name}' ($subKey): $nonPositional. " +
                            "Use placeholders posicionais (%1\$s, %2\$d, etc.) para suportar reordenação gramatical.",
                        nonPositional.isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun `all positional placeholders present in english default exist in translations`() {
        for (trans in translationLocales) {
            for (defaultEntry in defaultRes.entries) {
                val transEntry =
                    trans.entryMap[defaultEntry.name]
                        ?: error("Chave '${defaultEntry.name}' não encontrada em ${trans.parentDirName}/strings.xml (${trans.locale})")

                assertEquals(
                    "Tipo de recurso (string vs plurals) diverge para '${defaultEntry.name}' em " +
                        "${trans.parentDirName}/strings.xml (${trans.locale})",
                    defaultEntry.isPlural,
                    transEntry.isPlural,
                )

                if (!defaultEntry.isPlural) {
                    val defaultText = defaultEntry.texts["default"]?.replace("%%", "") ?: ""
                    val transText = transEntry.texts["default"]?.replace("%%", "") ?: ""

                    val defaultPlaceholders = POSITIONAL_PLACEHOLDER_REGEX.findAll(defaultText).map { it.value }.sorted().toList()
                    val transPlaceholders = POSITIONAL_PLACEHOLDER_REGEX.findAll(transText).map { it.value }.sorted().toList()

                    assertEquals(
                        "Placeholders posicionais divergem para chave '${defaultEntry.name}' em " +
                            "${trans.parentDirName}/strings.xml (${trans.locale})",
                        defaultPlaceholders,
                        transPlaceholders,
                    )
                } else {
                    val defaultPlaceholders =
                        defaultEntry.texts.values.firstOrNull()?.let {
                            POSITIONAL_PLACEHOLDER_REGEX.findAll(it.replace("%%", "")).map { m -> m.value }.sorted().toList()
                        } ?: emptyList()

                    for ((quantity, defaultText) in defaultEntry.texts) {
                        val transText =
                            transEntry.texts[quantity]?.replace("%%", "")
                                ?: error(
                                    "Forma de plural '$quantity' ausente para '${defaultEntry.name}' em " +
                                        "${trans.parentDirName}/strings.xml (${trans.locale})",
                                )
                        val cleanDefault = defaultText.replace("%%", "")

                        val expectedPlaceholders = POSITIONAL_PLACEHOLDER_REGEX.findAll(cleanDefault).map { it.value }.sorted().toList()
                        val transPlaceholders = POSITIONAL_PLACEHOLDER_REGEX.findAll(transText).map { it.value }.sorted().toList()

                        assertEquals(
                            "Placeholders posicionais divergem no plural '${defaultEntry.name}' [$quantity] em " +
                                "${trans.parentDirName}/strings.xml (${trans.locale})",
                            expectedPlaceholders,
                            transPlaceholders,
                        )
                    }

                    for ((quantity, transText) in transEntry.texts) {
                        val cleanTrans = transText.replace("%%", "")
                        val transPlaceholders = POSITIONAL_PLACEHOLDER_REGEX.findAll(cleanTrans).map { it.value }.sorted().toList()

                        assertEquals(
                            "Placeholders posicionais divergem no plural '${defaultEntry.name}' [$quantity] em " +
                                "${trans.parentDirName}/strings.xml (${trans.locale})",
                            defaultPlaceholders,
                            transPlaceholders,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `all plurals have required one and other forms across all locales`() {
        for (loc in allLocales) {
            val plurals = loc.entries.filter { it.isPlural }
            for (p in plurals) {
                assertTrue(
                    "Plural '${p.name}' em ${loc.parentDirName}/strings.xml (${loc.locale}) deve conter a forma 'one'",
                    p.texts.containsKey("one"),
                )
                assertTrue(
                    "Plural '${p.name}' em ${loc.parentDirName}/strings.xml (${loc.locale}) deve conter a forma 'other'",
                    p.texts.containsKey("other"),
                )
            }
        }
    }

    @Test
    fun `plurals format valid singular and plural outputs across all locales`() {
        val expectedSingular =
            mapOf(
                "en (default)" to
                    mapOf(
                        "browser_results_count_format" to "Showing 1 of 1 item",
                        "browser_folder_summary_format" to "1 item",
                        "playlists_item_count" to "1 video",
                        "network_smb_form_status_connected" to "Connected (1 share found)",
                    ),
                "pt-rBR" to
                    mapOf(
                        "browser_results_count_format" to "Exibindo 1 de 1 item",
                        "browser_folder_summary_format" to "1 item",
                        "playlists_item_count" to "1 vídeo",
                        "network_smb_form_status_connected" to "Conectado (1 share encontrado)",
                    ),
                "es" to
                    mapOf(
                        "browser_results_count_format" to "Mostrando 1 de 1 elemento",
                        "browser_folder_summary_format" to "1 elemento",
                        "playlists_item_count" to "1 video",
                        "network_smb_form_status_connected" to "Conectado (1 share encontrado)",
                    ),
            )

        val expectedPlural =
            mapOf(
                "en (default)" to
                    mapOf(
                        "browser_results_count_format" to "Showing 1 of 2 items",
                        "browser_folder_summary_format" to "2 items",
                        "playlists_item_count" to "2 videos",
                        "network_smb_form_status_connected" to "Connected (2 shares found)",
                    ),
                "pt-rBR" to
                    mapOf(
                        "browser_results_count_format" to "Exibindo 1 de 2 itens",
                        "browser_folder_summary_format" to "2 itens",
                        "playlists_item_count" to "2 vídeos",
                        "network_smb_form_status_connected" to "Conectado (2 shares encontrados)",
                    ),
                "es" to
                    mapOf(
                        "browser_results_count_format" to "Mostrando 1 de 2 elementos",
                        "browser_folder_summary_format" to "2 elementos",
                        "playlists_item_count" to "2 videos",
                        "network_smb_form_status_connected" to "Conectado (2 shares encontrados)",
                    ),
            )

        for (loc in allLocales) {
            val resultsEntry =
                loc.entryMap["browser_results_count_format"]
                    ?: error("browser_results_count_format ausente em ${loc.parentDirName}/strings.xml (${loc.locale})")
            val summaryEntry =
                loc.entryMap["browser_folder_summary_format"]
                    ?: error("browser_folder_summary_format ausente em ${loc.parentDirName}/strings.xml (${loc.locale})")
            val playlistsEntry =
                loc.entryMap["playlists_item_count"]
                    ?: error("playlists_item_count ausente em ${loc.parentDirName}/strings.xml (${loc.locale})")
            val smbEntry =
                loc.entryMap["network_smb_form_status_connected"]
                    ?: error("network_smb_form_status_connected ausente em ${loc.parentDirName}/strings.xml (${loc.locale})")

            val formattedResults1 = String.format(Locale.ROOT, resultsEntry.texts["one"] ?: "", 1, 1)
            val formattedSummary1 = String.format(Locale.ROOT, summaryEntry.texts["one"] ?: "", "", 1).removePrefix(" · ")
            val formattedPlaylists1 = String.format(Locale.ROOT, playlistsEntry.texts["one"] ?: "", 1)
            val formattedSmb1 = String.format(Locale.ROOT, smbEntry.texts["one"] ?: "", 1)

            val formattedResults2 = String.format(Locale.ROOT, resultsEntry.texts["other"] ?: "", 1, 2)
            val formattedSummary2 = String.format(Locale.ROOT, summaryEntry.texts["other"] ?: "", "", 2).removePrefix(" · ")
            val formattedPlaylists2 = String.format(Locale.ROOT, playlistsEntry.texts["other"] ?: "", 2)
            val formattedSmb2 = String.format(Locale.ROOT, smbEntry.texts["other"] ?: "", 2)

            expectedSingular[loc.locale]?.let { expected ->
                assertEquals(expected["browser_results_count_format"], formattedResults1)
                assertEquals(expected["browser_folder_summary_format"], formattedSummary1)
                assertEquals(expected["playlists_item_count"], formattedPlaylists1)
                assertEquals(expected["network_smb_form_status_connected"], formattedSmb1)
            }

            expectedPlural[loc.locale]?.let { expected ->
                assertEquals(expected["browser_results_count_format"], formattedResults2)
                assertEquals(expected["browser_folder_summary_format"], formattedSummary2)
                assertEquals(expected["playlists_item_count"], formattedPlaylists2)
                assertEquals(expected["network_smb_form_status_connected"], formattedSmb2)
            }

            for (formatted in listOf(
                formattedResults1,
                formattedSummary1,
                formattedPlaylists1,
                formattedSmb1,
                formattedResults2,
                formattedSummary2,
                formattedPlaylists2,
                formattedSmb2,
            )) {
                assertTrue(
                    "Formatação de plural em ${loc.parentDirName}/strings.xml (${loc.locale}) " +
                        "contém placeholder não resolvido: '$formatted'",
                    !formatted.contains("%1$") && !formatted.contains("%2$") &&
                        !formatted.contains("%d") && !formatted.contains("%s"),
                )
            }
        }
    }
}
