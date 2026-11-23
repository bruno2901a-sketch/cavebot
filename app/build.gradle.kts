plugins {
    id("com.android.application") version "8.6.1"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("kotlin-parcelize")
}

android {
    namespace = "com.rotabot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rotabot"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "1.9.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
