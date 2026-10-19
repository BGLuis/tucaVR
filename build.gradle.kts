plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
    // T9.1: KSP for Room (androidx.room:room-compiler) — faster than
    // kapt and the currently recommended path for Room 2.6+. Version
    // "1.9.0-1.0.13" confirmed to exist on Maven Central (repository
    // com.google.devtools.ksp) and matched to the Kotlin 1.9.0 already used in the
    // project (see the plugin above) — the suffix after the "-" is KSP's own
    // version, independent of the Kotlin version.
    id("com.google.devtools.ksp") version "1.9.0-1.0.13" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}
