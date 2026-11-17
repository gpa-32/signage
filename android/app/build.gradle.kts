plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Domyślny adres serwera wpisany na stałe w APK (tablet od razu pokazuje kod do dodania).
// Ustaw w pliku default_server.txt w katalogu głównym repozytorium albo w zmiennej DEFAULT_SERVER.
val defaultServer: String = (System.getenv("DEFAULT_SERVER")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("../default_server.txt").takeIf { it.exists() }?.readText()
    ?: "").trim().trimEnd('/')

android {
    namespace = "pl.signage.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.signage.player"
        minSdk = 21
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = "signage123"
            keyAlias = "signage"
            keyPassword = "signage123"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") { signingConfig = signingConfigs.getByName("release") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // skaner QR – wersja zgodna z Androidem 5+ (bez AndroidX)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.3.0")
    implementation("androidx.core:core:1.12.0") // wymagane przez skaner QR (uprawnienie do aparatu)
}
