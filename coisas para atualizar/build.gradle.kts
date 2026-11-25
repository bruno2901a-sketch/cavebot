plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
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

    // Gera BuildConfig.VERSION_NAME (usado na linha "VERSAO Rota ..." do log); no AGP 8.x vem desligado por padrão.
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
