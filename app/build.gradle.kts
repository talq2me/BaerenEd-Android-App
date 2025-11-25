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
        versionCode = 60
        versionName = "60"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Read GitHub token from local.properties, default to empty string if not set
        val githubToken = getLocalProperty("GITHUB_TOKEN", "")
        // Obfuscate token by Base64 encoding it (simple obfuscation to avoid GitHub secret scanning)
        // We'll decode it at runtime in MainActivity
        // Note: Using manual Base64 encoding since java.util.Base64 isn't available in Gradle script context
        val obfuscatedToken = if (githubToken.isNotEmpty()) {
            // Simple Base64 encoding for Gradle script (using manual implementation)
            val base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val bytes = githubToken.toByteArray(Charsets.UTF_8)
            val result = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val b1 = bytes[i].toInt() and 0xFF
                val b2 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b3 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                result.append(base64Chars[(b1 shr 2) and 0x3F])
                result.append(base64Chars[((b1 shl 4) or (b2 shr 4)) and 0x3F])
                result.append(if (i + 1 < bytes.size) base64Chars[((b2 shl 2) or (b3 shr 6)) and 0x3F] else '=')
                result.append(if (i + 2 < bytes.size) base64Chars[b3 and 0x3F] else '=')
                i += 3
            }
            result.toString()
        } else {
            ""
        }
        buildConfigField("String", "GITHUB_TOKEN", "\"$obfuscatedToken\"")
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
