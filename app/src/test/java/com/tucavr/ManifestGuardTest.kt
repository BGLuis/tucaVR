package com.tucavr

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sub-issue #215: Guard against unintended Android permissions and proprietary analytics dependencies.
 *
 * Scope note: This guard parses the source `src/main/AndroidManifest.xml` only. Permissions merged in
 * from library AARs (e.g. GeckoView) during manifest merging are outside this test's scope and would
 * need to be verified against `processDebugMainManifest` outputs if audited later.
 *
 * Fails if:
 * 1. An AndroidManifest.xml permission or feature is added without being recorded in permissions.txt.
 * 2. An entry in permissions.txt is stale (no longer present in AndroidManifest.xml).
 * 3. Any analytics / crash-reporting library from the denylist is introduced in app/build.gradle.kts.
 */
class ManifestGuardTest {
    @Test
    fun testManifestPermissionsAndFeaturesMatchBaseline() {
        val manifestFile = File(TestPaths.moduleDir, "src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml not found at ${manifestFile.absolutePath}", manifestFile.isFile)

        val baselineFile = File(TestPaths.moduleDir, "config/manifest-guards/permissions.txt")
        assertTrue("permissions.txt not found at ${baselineFile.absolutePath}", baselineFile.isFile)

        val manifestEntries = parseManifest(manifestFile.readText())
        val baselineEntries =
            baselineFile.readLines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toSet()

        val unexpected = manifestEntries - baselineEntries
        val stale = baselineEntries - manifestEntries

        assertTrue(
            "New unreviewed permission/feature in AndroidManifest.xml " +
                "(must be reviewed and added to config/manifest-guards/permissions.txt):\n" +
                unexpected.joinToString("\n"),
            unexpected.isEmpty(),
        )
        assertTrue(
            "Stale permission/feature in config/manifest-guards/permissions.txt not present in AndroidManifest.xml:\n" +
                stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    @Test
    fun testNoAnalyticsOrCrashReportingDependenciesInBuildGradle() {
        val buildGradleFile = File(TestPaths.moduleDir, "build.gradle.kts")
        assertTrue("build.gradle.kts not found at ${buildGradleFile.absolutePath}", buildGradleFile.isFile)

        val stripped = stripComments(buildGradleFile.readText())
        val findings = mutableListOf<String>()

        for (denied in ANALYTICS_DENYLIST) {
            val regex = Regex("""\b.*$denied.*""", RegexOption.IGNORE_CASE)
            val matches = regex.findAll(stripped)
            for (match in matches) {
                findings.add("${match.value.trim()} (matched '$denied')")
            }
        }

        assertTrue(
            "Forbidden analytics or crash reporting dependencies found in app/build.gradle.kts:\n" +
                findings.joinToString("\n"),
            findings.isEmpty(),
        )
    }

    companion object {
        val ANALYTICS_DENYLIST =
            listOf(
                "firebase-analytics",
                "firebase-crashlytics",
                "crashlytics",
                "sentry",
                "appcenter",
                "mixpanel",
                "amplitude",
                "flurry",
                "bugsnag",
            )

        fun parseManifest(manifestContent: String): Set<String> {
            val entries = mutableSetOf<String>()

            val permRegex = Regex("""<uses-permission[^>]*android:name\s*=\s*"([^"]+)"""")
            permRegex.findAll(manifestContent).forEach { match ->
                entries.add("permission:${match.groupValues[1]}")
            }

            val featureRegex = Regex("""<uses-feature[^>]*android:name\s*=\s*"([^"]+)"""")
            featureRegex.findAll(manifestContent).forEach { match ->
                entries.add("feature:${match.groupValues[1]}")
            }

            return entries
        }

        fun stripComments(source: String): String {
            return source
                .replace(Regex("""//.*"""), "")
                .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        }
    }
}
