pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven { url = java.net.URI("https://maven.mozilla.org/maven2/") } }
            filter { includeGroup("org.mozilla.geckoview") }
        }
    }
}
rootProject.name = "tucaVR"
include(":app")
