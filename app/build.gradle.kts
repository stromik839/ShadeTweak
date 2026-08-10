plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shadetweak.hyperos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shadetweak.hyperos"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
