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
        versionCode = 86
        versionName = "86"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Read encrypted GitHub token from local.properties
        // The token is encrypted using AES-256-CBC (use encrypt_token.py to encrypt it)
        // The decryption key is hardcoded in MainActivity.kt (safe to commit - it's just a key)
        val encryptedToken = getLocalProperty("ENCRYPTED_GITHUB_TOKEN", "")
        buildConfigField("String", "ENCRYPTED_GITHUB_TOKEN", "\"$encryptedToken\"")
        
        // Read Supabase configuration from local.properties
        // These will be embedded in the app at build time
        val supabaseUrl = getLocalProperty("SUPABASE_URL", "")
        val supabaseKey = getLocalProperty("SUPABASE_KEY", "")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
        
        // Exclude x86_64 to avoid 16KB alignment issues with ML Kit's native library
        // This is acceptable since x86_64 is primarily for emulators
        // Real Android devices use ARM architectures (armeabi-v7a, arm64-v8a)
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86"))
        }
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

    packaging {
        resources {
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/LICENSE-notice.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/AL2.0"
            excludes += "/META-INF/LGPL2.1"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
    
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // HTTP client for JSON downloading
    implementation(libs.okhttp)

    // JSON parsing
    implementation(libs.gson)
    implementation(libs.androidx.gridlayout)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    
    // ML Kit for OCR - using latest version with 16KB page size support
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Alternative: Use the newer unified ML Kit if available
    // implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
    
    // CameraX for camera functionality
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.8") // Mocking library for unit tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("io.mockk:mockk-android:1.13.8") // Mocking library for Android tests
}
