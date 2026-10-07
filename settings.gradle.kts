@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        maven {
            name = "aliucord"
            url = uri("https://maven.aliucord.com/releases")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "aliucord"
            url = uri("https://maven.aliucord.com/releases")
        }
    }
}

rootProject.name = "aliu-voice"
include(":plugins")

// Every directory under ./plugins with a build.gradle.kts is a plugin
rootDir.resolve("plugins")
    .listFiles { file -> file.isDirectory && file.resolve("build.gradle.kts").exists() }!!
    .forEach { include(":plugins:${it.name}") }
