import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Versie -----------------------------------------------------------
// versionName komt van -PversionName=1.2.3 (CI zet dit vanuit de git-tag).
// versionCode wordt daaruit afgeleid: 1.2.3 -> 10203, zodat nieuwere
// versies altijd een hogere code hebben, ook bij lokale builds.
val appVersionName: String = (findProperty("versionName") as String?) ?: "1.0.0"

fun versionCodeFrom(name: String): Int {
    val parts = name.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10000 +
        parts.getOrElse(1) { 0 } * 100 +
        parts.getOrElse(2) { 0 }
}

// ---- Signing ----------------------------------------------------------
// Lokaal: keystore.properties in de projectmap (niet in git).
// CI: omgevingsvariabelen KEYSTORE_FILE / KEYSTORE_PASSWORD (uit GitHub Secrets).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propName: String, envName: String): String? =
    keystoreProps.getProperty(propName) ?: System.getenv(envName)

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE")

android {
    namespace = "nl.basverbeek.videoaudioconverter"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.basverbeek.videoaudioconverter"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeFrom(appVersionName)
        versionName = appVersionName

        buildConfigField(
            "String",
            "GITHUB_REPO",
            "\"${(findProperty("githubRepo") as String?) ?: ""}\""
        )
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS") ?: "app"
                keyPassword = signingValue("keyPassword", "KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WAARSCHUWING: geen keystore gevonden, release wordt met de debug-key " +
                        "getekend. Updates over een eerdere versie heen zullen niet installeren."
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    // Per processortype een eigen APK (veel kleiner dan één APK met alles).
    // De in-app updater kiest automatisch de APK die bij het toestel past.
    //   arm64-v8a   = bijna alle telefoons
    //   armeabi-v7a = oude/goedkope telefoons
    //   x86_64      = emulators zoals BlueStacks
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            // Vereist door youtubedl-android: Python/FFmpeg moeten uitgepakt worden.
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // yt-dlp + Python + FFmpeg in de app
    val ytdlVersion = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlVersion")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
