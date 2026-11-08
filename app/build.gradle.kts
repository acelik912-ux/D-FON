plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.acelik.asistan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.acelik.asistan"
        minSdk = 29
        targetSdk = 34
        versionCode = runNo
        versionName = "1.0.$runNo"
    }

    // Sabit anahtar: yeni sürümler eskisinin üstüne kurulabilsin diye.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    // PDF'ten yazı çıkarma ve PDF birleştirme (açık kaynak, telefonda çalışır)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
