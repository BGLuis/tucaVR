import org.gradle.api.tasks.Exec
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // T9.1: Room uses KSP instead of kapt (see the rationale in the root build.gradle.kts).
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint")
}

// System version sync with version.properties at the root
val versionPropsFile = rootProject.file("version.properties")
val versionProps =
    Properties().apply {
        if (versionPropsFile.exists()) {
            versionPropsFile.inputStream().use { load(it) }
        }
    }
val defaultVersionName = versionProps.getProperty("versionName", "0.4.7")
val defaultVersionCode = versionProps.getProperty("versionCode", "470").toIntOrNull() ?: 470

val appVersionName = (project.findProperty("appVersionName") as? String)?.takeIf { it.isNotBlank() } ?: defaultVersionName
val appVersionCode = (project.findProperty("appVersionCode") as? String)?.toIntOrNull() ?: defaultVersionCode

// ccache speeds up native build (CMake/NDK) recompilations when available on PATH
// (e.g. hendrikmuhs/ccache-action in CI). Automatic detection instead of a manual flag —
// it does not affect local devs without ccache installed.
val ccachePath =
    System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.map { File(it, "ccache") }
        ?.firstOrNull { it.canExecute() }

val graphicsApi = project.findProperty("vrplayerGraphicsApi")?.toString() ?: "VULKAN"

android {
    namespace = "com.tucavr"
    compileSdk = 34

    buildFeatures {
        prefab = true
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    defaultConfig {
        applicationId = "com.tucavr"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GRAPHICS_API", "\"$graphicsApi\"")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += "-DANDROID_STL=c++_shared"
                // Stage 6 of the Vulkan migration plan (docs/VULKAN-MIGRATION-PLAN.md):
                // Vulkan is now the default backend (Stages 1-5 implemented and compiling).
                // The GLES path stays available through -PvrplayerGraphicsApi=GLES as a
                // fallback/regression path in case of unexpected behavior on the Quest 3 hardware.
                // See the revised ADR-003: the decision to keep GLES as a real fallback (two
                // paths kept) until complete validation on a headset.
                arguments += "-DVRPLAYER_GRAPHICS_API=$graphicsApi"
                if (project.findProperty("enableVulkanValidation") == "true") {
                    arguments += "-DENABLE_VK_VALIDATION_LAYERS=ON"
                }
                // Acelera rebuilds do build nativo (CI: hendrikmuhs/ccache-action popula o PATH).
                if (ccachePath != null) {
                    arguments += "-DCMAKE_C_COMPILER_LAUNCHER=${ccachePath.absolutePath}"
                    arguments += "-DCMAKE_CXX_COMPILER_LAUNCHER=${ccachePath.absolutePath}"
                }
            }
        }
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            if (project.findProperty("enableVulkanValidation") != "true") {
                excludes.add("**/libVkLayer_khronos_validation.so")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    ndkVersion = "26.3.11579264"

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.inputs.file("config/ui-guards/baseline.txt") }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        textReport = true
        htmlReport = true
    }
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    baseline.set(file("config/ktlint/baseline.xml"))
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.khronos.openxr:openxr_loader_for_android:1.0.34")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // T6.4: EncryptedSharedPreferences for SMB server credentials —
    // NEVER store a password in plain text (doc, section 6, "Credentials" warning).
    implementation("androidx.security:security-crypto:1.1.0")

    // T9.1: Room for the playback history (see app/src/main/java/com/tucavr/history/).
    // 2.6.1 is the latest stable release of the 2.6.x line compatible with Kotlin
    // 1.9.0 / KSP 1.9.0-1.0.13 (see the root build.gradle.kts) — we deliberately do not use the
    // 2.8.x line (newer), so as not to risk requiring a newer AGP/
    // Kotlin than the rest of the project already uses.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // T8.1 / T8.2: ExifInterface for photo metadata (EXIF orientation and XMP GPano 360)
    implementation("androidx.exifinterface:exifinterface:1.4.0")

    // GeckoView Engine (Mozilla) for Widevine DRM and off-screen rendering support
    implementation("org.mozilla.geckoview:geckoview-omni:122.0.20240205133611")

    // Jetpack Compose and Material 3
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    // Epic #102 / R4 / #106: Lottie intro animation sequence on main VR presentation panel
    implementation("com.airbnb.android:lottie:6.7.1")

    // JVM unit tests (app/src/test) — the file browser's pure logic
    // (MediaSorter, DirectoryNavigator, DirectoryLister, the ThumbnailGenerator
    // cache key) runs directly on the JVM, with no emulator/Robolectric,
    // because none of those classes touches real Android APIs in the
    // tested paths. See docs/TESTING-PLAN.md.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Android's org.json is a stub on the JVM unit-test classpath.
    testImplementation("org.json:json:20250517")

    // Pure-JVM SQLite driver (no Android dependency) to exercise the raw SQL of the
    // Room migrations (AppDatabase.MIGRATION_*_SQL) directly on the JVM, without Robolectric —
    // the same logic as the other testImplementation entries above: it runs without an
    // emulator because the migrations' SQL touches no real Android API.
    testImplementation("org.xerial:sqlite-jdbc:3.44.1.0")
}

val localProperties =
    Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
val androidSdkDir =
    localProperties.getProperty("sdk.dir")
        ?.let(::File)
        ?: System.getenv("ANDROID_HOME")?.let(::File)
        ?: System.getenv("ANDROID_SDK_ROOT")?.let(::File)

val rustBuildMode =
    (project.findProperty("rustBuildMode") as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv("RUST_BUILD_MODE")
        ?: "auto"

val buildRust =
    tasks.register<Exec>("buildRust") {
        group = "rust"
        description = "Cross-compiles Rust libraries and prepares JNI libraries (Host or Docker)."
        workingDir(rootProject.projectDir)

        inputs.files(
            rootProject.fileTree("rust") {
                exclude("**/target/**")
            },
        )
        inputs.file(rootProject.file("scripts/build-rust.sh"))
        inputs.property("rustBuildMode", rustBuildMode)

        outputs.dir(project.file("src/main/jniLibs/arm64-v8a"))

        commandLine("bash", rootProject.file("scripts/build-rust.sh").absolutePath)
        environment("RUST_BUILD_MODE", rustBuildMode)
        androidSdkDir?.let {
            environment("ANDROID_HOME", it.absolutePath)
            environment("ANDROID_SDK_ROOT", it.absolutePath)
        }
    }

val cleanRust =
    tasks.register<Exec>("cleanRust") {
        group = "rust"
        description = "Removes Rust build artifacts."
        workingDir(rootProject.projectDir)
        commandLine("bash", rootProject.file("scripts/build-rust.sh").absolutePath, "--clean")
        environment("RUST_BUILD_MODE", rustBuildMode)
    }

tasks.named("preBuild").configure {
    dependsOn(buildRust)
}

tasks.named("clean").configure {
    dependsOn(cleanRust)
}
