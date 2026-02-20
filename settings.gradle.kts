import java.util.Properties

val localProperties = Properties().apply {
    val file = file("local.properties")
    if (file.exists()) load(file.inputStream())
}

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Meta DAT SDK from GitHub Packages
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "" // not needed
                password = System.getenv("GITHUB_TOKEN")
                    ?: localProperties.getProperty("github_token")
            }
        }
        // RootEncoder RTMP library from JitPack
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "GlassesRelay"
include(":app")
