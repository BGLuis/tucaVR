package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Blocks literal text and colors in the UI: Android Lint only checks `HardcodedText` in XML, so
 * `Text("...")` and `view.text = "..."` in Kotlin would slip through. Earlier debt lives in the
 * baseline (`config/ui-guards/baseline.txt`); the test fails on new findings and on stale entries.
 */
class UiSourceGuardsTest {
    private data class Finding(
        val rule: String,
        val path: String,
        val snippet: String,
    ) {
        val key: String get() = "$rule|$path|$snippet"
    }

    @Test
    fun `file list does not use onSingleTapConfirmed`() {
        val fileAdapter = File("src/main/java/com/tucavr/screens/adapters/FileAdapter.kt")
        val target = if (fileAdapter.exists()) fileAdapter else File("app/src/main/java/com/tucavr/screens/adapters/FileAdapter.kt")
        assertTrue(target.exists())
        val content = target.readText()
        assertFalse(
            "FileAdapter must not use onSingleTapConfirmed (Issue #86)",
            content.contains("onSingleTapConfirmed"),
        )
    }

    @Test
    fun `file adapter re-selection triggers video play and uses payloads`() {
        val fileAdapter = File("src/main/java/com/tucavr/screens/adapters/FileAdapter.kt")
        val target = if (fileAdapter.exists()) fileAdapter else File("app/src/main/java/com/tucavr/screens/adapters/FileAdapter.kt")
        assertTrue(target.exists())
        val content = target.readText()
        assertTrue(
            "FileAdapter selectEntry must trigger onVideoClick when item was already selected",
            content.contains("if (previousSelectedPath == entry.path) {\n            onVideoClick(entry)"),
        )
        assertTrue(
            "FileAdapter must notify selection changes with PAYLOAD_SELECTION_CHANGED to prevent flicker",
            content.contains("PAYLOAD_SELECTION_CHANGED"),
        )
    }

    @Test
    fun `file adapter cancels the thumbnail job when a row is recycled`() {
        val fileAdapter = File("src/main/java/com/tucavr/screens/adapters/FileAdapter.kt")
        val target = if (fileAdapter.exists()) fileAdapter else File("app/src/main/java/com/tucavr/screens/adapters/FileAdapter.kt")
        assertTrue(target.exists())
        val content = target.readText()
        val recycled = content.substringAfter("override fun onViewRecycled(holder: ViewHolder) {", missingDelimiterValue = "")
        assertTrue("FileAdapter must override onViewRecycled (Issue #144)", recycled.isNotEmpty())
        assertTrue(
            "FileAdapter.onViewRecycled must cancel holder.thumbnailJob so off-screen rows free budget and connections",
            recycled.substringBefore("\n    }\n").contains("holder.thumbnailJob?.cancel()"),
        )
    }

    @Test
    fun `context rail view cancels the previous metadata job on re-selection`() {
        val file = File("src/main/java/com/tucavr/screens/components/ContextRailView.kt")
        val target = if (file.exists()) file else File("app/src/main/java/com/tucavr/screens/components/ContextRailView.kt")
        assertTrue(target.exists())
        val content = target.readText()
        assertTrue(
            "ContextRailView must cancel metadataJob before rebuilding the detail card (Issue #141)",
            content.substringAfter("fun updateState(force: Boolean = false) {").contains("metadataJob?.cancel()"),
        )
    }

    @Test
    fun `context rail view updateState is idempotent and guarded against repetitive destructions`() {
        val file = File("src/main/java/com/tucavr/screens/components/ContextRailView.kt")
        val target = if (file.exists()) file else File("app/src/main/java/com/tucavr/screens/components/ContextRailView.kt")
        assertTrue(target.exists())
        val content = target.readText()
        assertTrue(
            "ContextRailView must track renderedTab and renderedDetailPath to avoid destructive 15Hz rebuilds",
            content.contains("renderedTab") && content.contains("renderedDetailPath"),
        )
    }

    @Test
    fun `network home screen does not run infinite discovery loop on empty server list`() {
        val file = File("src/main/java/com/tucavr/screens/NetworkHomeScreen.kt")
        val target = if (file.exists()) file else File("app/src/main/java/com/tucavr/screens/NetworkHomeScreen.kt")
        assertTrue(target.exists())
        val content = target.readText()
        assertTrue(
            "NetworkHomeScreen must track hasRunInitialDiscovery",
            content.contains("hasRunInitialDiscovery"),
        )
        assertFalse(
            "NetworkHomeScreen must not call renderCurrent on discovery completion to avoid screen swapping loop",
            content.contains("startInlineDiscovery(refreshUI = true)"),
        )
    }

    @Test
    fun `history resume passes sizeBytes from history key to avoid split records`() {
        listOf("HomeScreen.kt", "ContinueWatchingScreen.kt").forEach { fileName ->
            val file = File("src/main/java/com/tucavr/screens/$fileName")
            val target = if (file.exists()) file else File("app/src/main/java/com/tucavr/screens/$fileName")
            assertTrue(target.exists())
            val content = target.readText()
            val resumeFn =
                content.substringAfter("private fun resumeFromHistory(entry: PlaybackHistory) {")
                    .substringBefore("\n    private fun ")
            assertTrue(
                "$fileName resumeFromHistory must extract sizeBytes via sizeBytesFromKey",
                resumeFn.contains("entry.sizeBytesFromKey()"),
            )
            val playMethods = listOf("playFile", "playSmb", "playFtp", "playSftp", "playNfs", "playDlna", "playWebdav")
            playMethods.forEach { method ->
                val callRegex = Regex("""activity\.$method\([^)]*sizeBytes\s*=\s*sizeBytes[^)]*\)""", RegexOption.DOT_MATCHES_ALL)
                assertTrue(
                    "$fileName resumeFromHistory must pass sizeBytes = sizeBytes in activity.$method()",
                    callRegex.containsMatchIn(resumeFn),
                )
            }
            assertTrue(
                "$fileName resumeFromHistory must call activity.playUrl for HTTP",
                resumeFn.contains("activity.playUrl("),
            )
        }
    }

    @Test
    fun `foveated rendering does not call nativeSetFoveationEnabled to prevent mode override`() {
        listOf("VRActivity.kt", "screens/SettingsScreen.kt").forEach { fileName ->
            val target = File(TestPaths.mainSources, "com/tucavr/$fileName")
            assertTrue("File must exist: ${target.path}", target.exists())
            val content = target.readText()
            val calls = Regex("""(?<!external fun\s)\bnativeSetFoveationEnabled\s*\(""").findAll(content).toList()
            assertTrue(
                "$fileName must not call nativeSetFoveationEnabled because it overrides granular modes to Auto (Issue #41)",
                calls.isEmpty(),
            )
        }
    }

    @Test
    fun `user-facing text comes from string resources`() = assertMatchesBaseline(RULE_TEXT)

    @Test
    fun `compose colors come from BrowserColors tokens`() = assertMatchesBaseline(RULE_COLOR)

    @Test
    fun `scanner flags the literals it is supposed to flag`() {
        val source =
            """
            Text("Save")
            Text(text = "Cancel", color = x)
            Icon(contentDescription = "Back")
            label.text = "AQ: MED"
            view.setText("Hello")
            val text = "ignored declaration"
            Text(stringResource(R.string.ok))
            clock.text = "00:00"
            Text("${'$'}count")
            """.trimIndent()

        assertEquals(
            listOf("Save", "Cancel", "Back", "AQ: MED", "Hello"),
            findHardcodedText(source),
        )
    }

    @Test
    fun `scanner flags literal colors but not theme tokens`() {
        val source =
            """
            Color(0xFF123456)
            Color.White
            Color.Black.copy(alpha = 0.5f)
            Color.Transparent
            Color(VoidTheme.colorText)
            """.trimIndent()

        assertEquals(
            listOf("Color(0xFF123456)", "Color.White", "Color.Black"),
            findLiteralColors(source),
        )
    }

    @Test
    fun `color grading constants maintain strict parity between header and shaders`() {
        val candidates =
            listOf(
                TestPaths.moduleDir.parentFile,
                TestPaths.moduleDir,
                File(System.getProperty("user.dir") ?: "."),
            )
        val rootDir =
            candidates.firstOrNull { File(it, "native/include/color_grading.h").isFile }
                ?: error("Repository root with native/include/color_grading.h not found")

        val headerText = File(rootDir, "native/include/color_grading.h").readText()
        val videoFrag = File(rootDir, "native/shaders/vulkan/video.frag").readText()
        val stereoFrag = File(rootDir, "native/shaders/vulkan/stereo.frag").readText()
        val cubemapFrag = File(rootDir, "native/shaders/vulkan/stereo_cubemap.frag").readText()
        val layerShaders =
            File(rootDir, "native/shaders/vulkan/video_common.glsl").readText() +
                File(rootDir, "native/shaders/vulkan/video_layer.frag").readText()

        val shaders =
            listOf(
                "video.frag" to videoFrag,
                "stereo.frag" to stereoFrag,
                "stereo_cubemap.frag" to cubemapFrag,
                "video_layer.frag with video_common.glsl" to layerShaders,
            )

        // 1. Verify Hable Filmic constants in header
        assertTrue("color_grading.h must define kHableA = 0.15f", headerText.contains("kHableA = 0.15f"))
        assertTrue("color_grading.h must define kHableB = 0.50f", headerText.contains("kHableB = 0.50f"))
        assertTrue("color_grading.h must define kHableC = 0.10f", headerText.contains("kHableC = 0.10f"))
        assertTrue("color_grading.h must define kHableD = 0.20f", headerText.contains("kHableD = 0.20f"))
        assertTrue("color_grading.h must define kHableE = 0.02f", headerText.contains("kHableE = 0.02f"))
        assertTrue("color_grading.h must define kHableF = 0.30f", headerText.contains("kHableF = 0.30f"))

        // 2. Verify PQ EOTF constants in header
        assertTrue("color_grading.h must define kPqM1", headerText.contains("kPqM1 = 0.1593017578125f"))
        assertTrue("color_grading.h must define kPqM2", headerText.contains("kPqM2 = 78.84375f"))
        assertTrue("color_grading.h must define kPqC1", headerText.contains("kPqC1 = 0.8359375f"))
        assertTrue("color_grading.h must define kPqC2", headerText.contains("kPqC2 = 18.8515625f"))
        assertTrue("color_grading.h must define kPqC3", headerText.contains("kPqC3 = 18.6875f"))

        // 3. Verify tonemapping parameters in header
        assertTrue("color_grading.h must define kW = 40.0f", headerText.contains("kW = 40.0f"))
        assertTrue("color_grading.h must define kExposure = 3.0f", headerText.contains("kExposure = 3.0f"))
        assertTrue("color_grading.h must define kSCurveStrength = 0.18f", headerText.contains("kSCurveStrength = 0.18f"))

        // 4. Verify parity across all fragment shaders
        for ((name, code) in shaders) {
            assertTrue("$name must define Hable constant A = 0.15", code.contains("const float A = 0.15;"))
            assertTrue("$name must define Hable constant B = 0.50", code.contains("const float B = 0.50;"))
            assertTrue("$name must define Hable constant C = 0.10", code.contains("const float C = 0.10;"))
            assertTrue("$name must define Hable constant D = 0.20", code.contains("const float D = 0.20;"))
            assertTrue("$name must define Hable constant E = 0.02", code.contains("const float E = 0.02;"))
            assertTrue("$name must define Hable constant F = 0.30", code.contains("const float F = 0.30;"))

            assertTrue("$name must define PQ m1 = 0.1593017578125", code.contains("const float m1 = 0.1593017578125;"))
            assertTrue("$name must define PQ m2 = 78.84375", code.contains("const float m2 = 78.84375;"))
            assertTrue("$name must define PQ c1 = 0.8359375", code.contains("const float c1 = 0.8359375;"))
            assertTrue("$name must define PQ c2 = 18.8515625", code.contains("const float c2 = 18.8515625;"))
            assertTrue("$name must define PQ c3 = 18.6875", code.contains("const float c3 = 18.6875;"))

            assertTrue("$name must define kW = 40.0", code.contains("const float kW = 40.0;"))
            assertTrue("$name must define kExposure = 3.0", code.contains("const float kExposure = 3.0;"))
            assertTrue("$name must apply Cubic S-Curve at 0.18 for HDR", code.contains("ApplyCubicSCurve(gammaPeak, 0.18)"))
            assertTrue("$name must scale the luminance into the exposure", code.contains("float exposure = kExposure * luminance;"))
            assertTrue(
                "$name must scale every channel by the mapped peak to keep the hue",
                code.contains("vec3 tonemapped = scene * (mappedPeak / max(peak, 1e-5));"),
            )
            assertTrue(
                "$name must pass the luminance setting to the tone mapping",
                Regex("""TonemapHdrToSdr\(color, [\w.]+, [\w.]*[hH]drLuminance\)""").containsMatchIn(code),
            )

            // 5. The eye swapchain is *_SRGB: output must be linearized or the hardware encodes video twice.
            assertTrue("$name must define SrgbEotf", code.contains("vec3 SrgbEotf(vec3 c)"))
            assertTrue("$name SrgbEotf must use the exact piecewise curve", code.contains("c / 12.92") && code.contains("vec3(2.4)"))
            val finalOutput = code.lines().last { it.trim().startsWith("outColor = vec4(") }
            assertTrue(
                "$name must linearize with SrgbEotf in its final outColor write",
                finalOutput.contains("SrgbEotf(color)"),
            )
        }
    }

    @Test
    fun `hdr luminance range matches native and reaches it at startup`() {
        val rootDir = TestPaths.moduleDir.parentFile
        val header = File(rootDir, "native/include/color_grading.h").readText()
        assertTrue(header.contains("kHdrLuminanceMin = ${HdrLuminanceStore.MIN}f"))
        assertTrue(header.contains("kHdrLuminanceDefault = ${HdrLuminanceStore.DEFAULT}f"))
        assertTrue(header.contains("kHdrLuminanceMax = ${HdrLuminanceStore.MAX}f"))

        val activity = File(TestPaths.mainSources, "com/tucavr/VRActivity.kt").readText()
        assertTrue(
            "VRActivity must push the persisted HDR luminance, or it is lost on restart (Issue #247)",
            activity.contains("nativeSetHdrLuminance(HdrLuminanceStore(this).get())"),
        )
    }

    @Test
    fun `gamma-coded overlays are not encoded twice by the sRGB eye swapchain`() {
        val rootDir = TestPaths.moduleDir.parentFile
        val renderer = File(rootDir, "native/src/vr_player_app_vulkan.cpp").readText()
        val ambientFrag = File(rootDir, "native/shaders/vulkan/ambient.frag").readText()

        assertTrue(
            "The PGS image must use an sRGB format so sampling decodes its gamma-coded RGB (Issue #248)",
            Regex("""CreateUiImage\([^;]*state\.pgsSubtitleImage\b[^;]*VK_FORMAT_R8G8B8A8_SRGB""").containsMatchIn(renderer),
        )
        assertTrue(
            "ambient.frag must define the exact piecewise sRGB EOTF (Issue #248)",
            ambientFrag.contains("vec3 SrgbEotf(vec3 c)") && ambientFrag.contains("c / 12.92") && ambientFrag.contains("vec3(2.4)"),
        )
        val finalOutput = ambientFrag.lines().last { it.trim().startsWith("outColor = vec4(") }
        assertTrue(
            "ambient.frag must linearize the halo in its final outColor write (Issue #248)",
            finalOutput.contains("SrgbEotf(color)"),
        )
    }

    @Test
    fun `player controls guards view writes to prevent redundant invalidations`() {
        val file = File("src/main/java/com/tucavr/VRControlsPresentation.kt")
        val target = if (file.exists()) file else File("app/src/main/java/com/tucavr/VRControlsPresentation.kt")
        assertTrue(target.exists())
        val content = target.readText()
        assertTrue(
            "VRControlsPresentation must guard view updates using viewCache (Issue #152)",
            content.contains("viewCache.shouldWriteProgress") &&
                content.contains("viewCache.shouldWriteTime") &&
                content.contains("viewCache.shouldWriteTotalTime") &&
                content.contains("viewCache.shouldWritePlaying") &&
                content.contains("viewCache.reset()"),
        )
    }

    private fun assertMatchesBaseline(rule: String) {
        val current = scanMainSources().filter { it.rule == rule }.map { it.key }
        val baseline = readBaseline().filter { it.startsWith("$rule|") }

        val added = minusEach(current, baseline)
        val stale = minusEach(baseline, current)

        assertTrue(
            "New '$rule' findings (use a string resource / theme token, or, only if legitimate, " +
                "add them to the baseline):\n${added.joinToString("\n")}",
            added.isEmpty(),
        )
        assertTrue(
            "'$rule' baseline entries that no longer exist (remove them from " +
                "config/ui-guards/baseline.txt):\n${stale.joinToString("\n")}",
            stale.isEmpty(),
        )
    }

    private fun scanMainSources(): List<Finding> =
        TestPaths.mainSources
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { file ->
                val path = file.relativeTo(TestPaths.mainSources).invariantSeparatorsPath
                val source = stripComments(file.readText())
                val text = findHardcodedText(source).map { Finding(RULE_TEXT, path, it) }
                val colors =
                    if (isComposeFile(path, source)) {
                        findLiteralColors(source).map { Finding(RULE_COLOR, path, it) }
                    } else {
                        emptyList()
                    }
                text + colors
            }.toList()

    private fun readBaseline(): List<String> {
        val file = File(TestPaths.moduleDir, "config/ui-guards/baseline.txt")
        assertTrue("Baseline not found: ${file.absolutePath}", file.isFile)
        return file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    private fun minusEach(
        from: List<String>,
        remove: List<String>,
    ): List<String> {
        val pending = remove.toMutableList()
        return from.filter { !pending.remove(it) }
    }

    private fun stripComments(source: String): String =
        source
            .replace(BLOCK_COMMENT, "")
            .replace(LINE_COMMENT, "")

    private fun isComposeFile(
        path: String,
        source: String,
    ): Boolean =
        path != COLOR_TOKENS_FILE &&
            (source.contains("@Composable") || source.contains("androidx.compose.ui.graphics.Color"))

    private fun findHardcodedText(source: String): List<String> =
        TEXT_SINKS
            .flatMap { it.findAll(source).toList() }
            .distinctBy { it.groups[1]!!.range.first }
            .sortedBy { it.range.first }
            .map { it.groupValues[1] }
            .filter { hasVisibleLetters(it) }

    private fun findLiteralColors(source: String): List<String> =
        COLOR_LITERALS
            .flatMap { it.findAll(source).toList() }
            .sortedBy { it.range.first }
            .map { it.value }

    private fun hasVisibleLetters(literal: String): Boolean =
        literal
            .replace(TEMPLATE_EXPRESSION, "")
            .replace(TEMPLATE_VARIABLE, "")
            .any { it.isLetter() }

    private companion object {
        const val RULE_TEXT = "hardcoded-text"
        const val RULE_COLOR = "literal-color"
        const val COLOR_TOKENS_FILE = "com/tucavr/browser/ui/BrowserColors.kt"

        const val QUOTED = "\"((?:[^\"\\\\\\n]|\\\\.)*)\""

        val BLOCK_COMMENT = Regex("""(?m)^[ \t]*/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""(?m)^[ \t]*//.*$""")
        val TEMPLATE_EXPRESSION = Regex("""\$\{[^}]*}""")
        val TEMPLATE_VARIABLE = Regex("""\$[A-Za-z_]\w*""")

        val TEXT_SINKS =
            listOf(
                Regex("""\b(?:Text|BasicText)\(\s*(?:text\s*=\s*)?$QUOTED"""),
                Regex("""(?<!\bval )(?<!\bvar )\b(?:text|hint|contentDescription)\s*=\s*$QUOTED"""),
                Regex("""\b(?:setText|setHint|setContentDescription)\(\s*$QUOTED"""),
            )

        val COLOR_LITERALS =
            listOf(
                Regex("""\bColor\(\s*0x[0-9A-Fa-f]+[uUlL]*\s*\)"""),
                Regex(
                    """\bColor\.(?:White|Black|Gray|DarkGray|LightGray|Red|Green|Blue|""" +
                        """Yellow|Cyan|Magenta)\b""",
                ),
            )
    }
}
