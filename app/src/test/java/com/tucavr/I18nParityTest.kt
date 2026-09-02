package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
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
        val texts: Map<String, String> // "default" -> texto para <string>; quantity -> texto para <plurals>
    )

    data class LocaleResources(
        val locale: String,
        val file: File,
        val entries: List<StringEntry>
    ) {
        val keys: List<String> = entries.map { it.name }
        val keySet: Set<String> = keys.toSet()
        val entryMap: Map<String, StringEntry> = entries.associateBy { it.name }
    }

    companion object {
        private lateinit var defaultRes: LocaleResources
        private lateinit var ptBrRes: LocaleResources
        private lateinit var esRes: LocaleResources

        private val POSITIONAL_PLACEHOLDER_REGEX = Regex("%[0-9]+\\$[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]")
        private val NON_POSITIONAL_PLACEHOLDER_REGEX = Regex("%(?![0-9]+\\$)[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]")

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val resDir = resolveResDirectory()
            defaultRes = parseStringsXml("en (default)", File(resDir, "values/strings.xml"))
            ptBrRes = parseStringsXml("pt-rBR", File(resDir, "values-pt-rBR/strings.xml"))
            esRes = parseStringsXml("es", File(resDir, "values-es/strings.xml"))
        }

        private fun resolveResDirectory(): File {
            val userDir = File(System.getProperty("user.dir") ?: ".")
            val candidateApp = File(userDir, "src/main/res")
            if (candidateApp.isDirectory) return candidateApp
            val candidateRoot = File(userDir, "app/src/main/res")
            if (candidateRoot.isDirectory) return candidateRoot
            error("Diretório de resources não encontrado a partir de: ${userDir.absolutePath}")
        }

        private fun parseStringsXml(locale: String, file: File): LocaleResources {
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
        val ptBrKeys = ptBrRes.keySet
        val esKeys = esRes.keySet

        val missingInPt = defaultKeys - ptBrKeys
        val extraInPt = ptBrKeys - defaultKeys
        assertTrue(
            "Discrepância de chaves em values-pt-rBR/strings.xml: faltantes=$missingInPt, extras=$extraInPt",
            missingInPt.isEmpty() && extraInPt.isEmpty()
        )

        val missingInEs = defaultKeys - esKeys
        val extraInEs = esKeys - defaultKeys
        assertTrue(
            "Discrepância de chaves em values-es/strings.xml: faltantes=$missingInEs, extras=$extraInEs",
            missingInEs.isEmpty() && extraInEs.isEmpty()
        )

        assertEquals("Total de chaves em pt-BR deve ser idêntico ao default", defaultKeys.size, ptBrKeys.size)
        assertEquals("Total de chaves em ES deve ser idêntico ao default", defaultKeys.size, esKeys.size)
    }

    @Test
    fun `key declaration order strictly mirrors default values strings_xml`() {
        assertEquals(
            "A ordem das chaves em values-pt-rBR/strings.xml deve espelhar exatamente values/strings.xml",
            defaultRes.keys,
            ptBrRes.keys
        )
        assertEquals(
            "A ordem das chaves em values-es/strings.xml deve espelhar exatamente values/strings.xml",
            defaultRes.keys,
            esRes.keys
        )
    }

    @Test
    fun `no non-positional placeholders are used in any locale`() {
        val locales = listOf(defaultRes, ptBrRes, esRes)

        for (loc in locales) {
            for (entry in loc.entries) {
                for ((subKey, text) in entry.texts) {
                    val cleanText = text.replace("%%", "")
                    val nonPositional = NON_POSITIONAL_PLACEHOLDER_REGEX.findAll(cleanText).map { it.value }.toList()
                    assertTrue(
                        "Placeholder não-posicional encontrado em ${loc.file.name} na chave '${entry.name}' ($subKey): $nonPositional. " +
                            "Use placeholders posicionais (%1\$s, %2\$d, etc.) para suportar reordenação gramatical.",
                        nonPositional.isEmpty()
                    )
                }
            }
        }
    }

    @Test
    fun `all positional placeholders present in english default exist in translations`() {
        val translations = listOf(ptBrRes, esRes)

        for (trans in translations) {
            for (defaultEntry in defaultRes.entries) {
                val transEntry = trans.entryMap[defaultEntry.name]
                    ?: error("Chave '${defaultEntry.name}' não encontrada em ${trans.locale}")

                assertEquals(
                    "Tipo de recurso (string vs plurals) diverge para '${defaultEntry.name}' em ${trans.locale}",
                    defaultEntry.isPlural,
                    transEntry.isPlural
                )

                if (!defaultEntry.isPlural) {
                    val defaultText = defaultEntry.texts["default"]?.replace("%%", "") ?: ""
                    val transText = transEntry.texts["default"]?.replace("%%", "") ?: ""

                    val defaultPlaceholders = POSITIONAL_PLACEHOLDER_REGEX.findAll(defaultText).map { it.value }.sorted().toList()
                    val transPlaceholders = POSITIONAL_PLACEHOLDER_REGEX.findAll(transText).map { it.value }.sorted().toList()

                    assertEquals(
                        "Placeholders posicionais divergem para chave '${defaultEntry.name}' em ${trans.locale}",
                        defaultPlaceholders,
                        transPlaceholders
                    )
                } else {
                    for ((quantity, defaultText) in defaultEntry.texts) {
                        val transText = transEntry.texts[quantity]?.replace("%%", "")
                            ?: error("Forma de plural '$quantity' ausente para '${defaultEntry.name}' em ${trans.locale}")
                        val cleanDefault = defaultText.replace("%%", "")

                        val defaultPlaceholders = POSITIONAL_PLACEHOLDER_REGEX.findAll(cleanDefault).map { it.value }.sorted().toList()
                        val transPlaceholders = POSITIONAL_PLACEHOLDER_REGEX.findAll(transText).map { it.value }.sorted().toList()

                        assertEquals(
                            "Placeholders posicionais divergem no plural '${defaultEntry.name}' [$quantity] em ${trans.locale}",
                            defaultPlaceholders,
                            transPlaceholders
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `all plurals have required one and other forms across all locales`() {
        val locales = listOf(defaultRes, ptBrRes, esRes)

        for (loc in locales) {
            val plurals = loc.entries.filter { it.isPlural }
            for (p in plurals) {
                assertTrue(
                    "Plural '${p.name}' em ${loc.locale} deve conter a forma 'one'",
                    p.texts.containsKey("one")
                )
                assertTrue(
                    "Plural '${p.name}' em ${loc.locale} deve conter a forma 'other'",
                    p.texts.containsKey("other")
                )
            }
        }
    }
}
