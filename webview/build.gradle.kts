plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.talq2me.baerened.webview"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.talq2me.baerened.webview"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}
