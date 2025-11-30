plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Read GitHub token from local.properties
fun getLocalProperty(key: String, defaultValue: String = ""): String {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.readLines().forEach { line ->
            if (line.startsWith("$key=")) {
                return line.substringAfter("=").trim()
            }
        }
    }
    return defaultValue
}

android {
    namespace = "com.talq2me.baerened"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.talq2me.baerened"
        minSdk = 23
        targetSdk = 35
        versionCode = 67
        versionName = "67"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Read encrypted GitHub token from local.properties
        // The token is encrypted using AES-256-CBC (use encrypt_token.py to encrypt it)
        // The decryption key is hardcoded in MainActivity.kt (safe to commit - it's just a key)
        val encryptedToken = getLocalProperty("ENCRYPTED_GITHUB_TOKEN", "")
        buildConfigField("String", "ENCRYPTED_GITHUB_TOKEN", "\"$encryptedToken\"")
    }
    
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation("com.talq2me.baeren:settings-contract:1.0.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // HTTP client for JSON downloading
    implementation(libs.okhttp)

    // JSON parsing
    implementation(libs.gson)
    implementation(libs.androidx.gridlayout)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.8") // Mocking library for unit tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("io.mockk:mockk-android:1.13.8") // Mocking library for Android tests
}
