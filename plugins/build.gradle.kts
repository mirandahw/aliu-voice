@file:Suppress("UnstableApiUsage")

import com.aliucord.gradle.AliucordExtension
import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension

subprojects {
    val libs = rootProject.libs

    apply {
        plugin(libs.plugins.android.library.get().pluginId)
        plugin(libs.plugins.aliucord.plugin.get().pluginId)
        plugin(libs.plugins.kotlin.android.get().pluginId)
    }

    configure<LibraryExtension> {
        namespace = "com.mirandahw.${project.name.lowercase()}"
        compileSdk = 36

        defaultConfig {
            minSdk = 24
        }

        buildFeatures {
            buildConfig = true
            resValues = true
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    configure<AliucordExtension> {
        author("mirandahw", 0L, hyperlink = false)
        github("https://github.com/mirandahw/aliu-voice")
    }

    configure<KotlinAndroidExtension> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            optIn.add("kotlin.RequiresOptIn")
        }
    }

    @Suppress("unused")
    dependencies {
        val compileOnly by configurations

        compileOnly(libs.discord)
        compileOnly(libs.aliucord)
        compileOnly(libs.kotlin.stdlib)
    }
}
