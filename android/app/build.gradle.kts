plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.irfan.rabta"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.irfan.rabta"
        minSdk = 26
        targetSdk = 34
        // Every CI run numbers its build, so a published Rabta is always newer than the one it updates.
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (ciRun != null) 1000 + ciRun else 34
        versionName = "2.0.${ciRun ?: 0}"
        manifestPlaceholders["appLabel"] = "Rabta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // One stable key for every test build, so a new Rabta Test APK installs over the previous one.
    // CI restores it from GitHub Secrets; without it, Android's per-machine debug key is used as before.
    val testKeystore = System.getenv("RABTA_TEST_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    // The public app has its own key, kept only in GitHub Secrets and never used for debuggable builds.
    val releaseKeystore = System.getenv("RABTA_RELEASE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (testKeystore != null) create("rabtaTest") {
            storeFile = testKeystore
            storePassword = System.getenv("RABTA_TEST_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RABTA_TEST_KEY_ALIAS") ?: "rabta-test"
            keyPassword = System.getenv("RABTA_TEST_KEYSTORE_PASSWORD")
        }
        if (releaseKeystore != null) create("rabtaRelease") {
            storeFile = releaseKeystore
            storePassword = System.getenv("RABTA_RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RABTA_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "rabta"
            keyPassword = System.getenv("RABTA_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("RABTA_RELEASE_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") { if (testKeystore != null) signingConfig = signingConfigs.getByName("rabtaTest") }
        // Without the release key Gradle leaves the public APK unsigned, and CI publishes nothing.
        getByName("release") {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("rabtaRelease")
            // Phones are ARM; the x86 libraries only serve emulators and nearly double the download.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        create("device") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testing"
            versionNameSuffix = "-test"
            manifestPlaceholders["appLabel"] = "Rabta Test"
            matchingFallbacks += listOf("debug")
        }
    }
    // Reuse the existing debug network configuration for the isolated device-test build.
    sourceSets.getByName("device").manifest.srcFile("src/debug/AndroidManifest.xml")
    sourceSets.getByName("device").res.srcDir("src/debug/res")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:114.5735.07")
}

