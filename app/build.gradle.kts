import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // T9.1: Room usa KSP em vez de kapt (ver justificativa no build.gradle.kts raiz).
    id("com.google.devtools.ksp")
}

// Sincronização de versão do sistema com version.properties na raiz
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}
val defaultVersionName = versionProps.getProperty("versionName", "0.4.7")
val defaultVersionCode = versionProps.getProperty("versionCode", "470").toIntOrNull() ?: 470

val appVersionName = (project.findProperty("appVersionName") as? String)?.takeIf { it.isNotBlank() } ?: defaultVersionName
val appVersionCode = (project.findProperty("appVersionCode") as? String)?.toIntOrNull() ?: defaultVersionCode

android {
    namespace = "com.tucavr"
    compileSdk = 34

    buildFeatures {
        prefab = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.tucavr"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += "-DANDROID_STL=c++_shared"
                // Estagio 6 do plano de migracao Vulkan (docs/VULKAN-MIGRATION-PLAN.md):
                // Vulkan e agora o backend padrao (Estagios 1-5 implementados e compilando).
                // O caminho GLES continua disponivel via -PvrplayerGraphicsApi=GLES para
                // fallback/regressao em caso de comportamento inesperado no hardware Quest 3.
                // Ver ADR-003 revisado: decisao de manter GLES como fallback real (dois
                // caminhos mantidos) ate validacao completa em headset.
                arguments += "-DVRPLAYER_GRAPHICS_API=${project.findProperty("vrplayerGraphicsApi") ?: "VULKAN"}"
                if (project.findProperty("enableVulkanValidation") == "true") {
                    arguments += "-DENABLE_VK_VALIDATION_LAYERS=ON"
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
                "proguard-rules.pro"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.khronos.openxr:openxr_loader_for_android:1.0.34")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // T6.4: EncryptedSharedPreferences para credenciais de servidores SMB —
    // NUNCA armazenar senha em texto plano (doc, secao 6, aviso "Credenciais").
    implementation("androidx.security:security-crypto:1.1.0")

    // T9.1: Room para o historico de reproducao (ver app/src/main/java/com/tucavr/history/).
    // 2.6.1 e a ultima release estavel da linha 2.6.x compativel com Kotlin
    // 1.9.0 / KSP 1.9.0-1.0.13 (ver build.gradle.kts raiz) — nao usamos a
    // linha 2.8.x (mais nova) de proposito, para nao arriscar exigir um AGP/
    // Kotlin mais recente do que o resto do projeto ja usa.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // T8.1 / T8.2: ExifInterface para metadados de fotos (orientação EXIF e XMP GPano 360)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // JVM unit tests (app/src/test) — logica pura do file browser
    // (MediaSorter, DirectoryNavigator, DirectoryLister, cache-key do
    // ThumbnailGenerator) roda direto na JVM, sem emulador/Robolectric,
    // porque nenhuma dessas classes toca em APIs Android reais nos
    // caminhos testados. Ver docs/TESTING-PLAN.md.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // R-05 (PHASE-0.4-08-VERIFICACAO-PROFUNDA.md): driver SQLite puro-JVM (sem dependencia
    // Android) para exercitar o SQL bruto das migrations do Room (AppDatabase.MIGRATION_*_SQL)
    // direto na JVM, sem Robolectric — mesma logica das outras testImplementation acima: roda
    // sem emulador porque o SQL das migrations nao toca em nenhuma API Android real.
    testImplementation("org.xerial:sqlite-jdbc:3.44.1.0")
}

// Placeholder for Rust integration (via Mozilla plugin or custom task)
tasks.register("buildRust") {
    group = "rust"
    description = "Builds the Rust library."
    doLast {
        println("Executing Rust build placeholder...")
    }
}
