plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shadetweak.hyperos"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.shadetweak.hyperos"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildFeatures {
        // BuildConfig.VERSION_NAME пишется в лог LSPosed и в статус
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    // Надається фреймворком LSPosed під час виконання - у фінальний apk НЕ пакується
    compileOnly("de.robv.android.xposed:api:82")
}
