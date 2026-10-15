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
        versionCode = 31
        versionName = "1.8"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:114.5735.07")
}
