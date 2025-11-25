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
        versionCode = 51
        versionName = "51"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Read GitHub token from local.properties, default to empty string if not set
        val githubToken = getLocalProperty("GITHUB_TOKEN", "")
        buildConfigField("String", "GITHUB_TOKEN", "\"$githubToken\"")
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
