plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.czcheck.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.czcheck.scanner"
        minSdk = 21
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Один постоянный ключ подписи, чтобы новые версии ставились поверх старых
    // без удаления приложения. Это внутренний инструмент, ключ лежит в репозитории.
    signingConfigs {
        create("shared") {
            storeFile = file("czscanner.jks")
            storePassword = "czscanner123"
            keyAlias = "czscanner"
            keyPassword = "czscanner123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
