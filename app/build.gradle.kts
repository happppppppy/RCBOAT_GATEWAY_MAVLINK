plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")            // or KSP (see alt below)
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.example.gateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gateway"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Kotlin stdlib pulled automatically, but explicit is fine
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")

    // (Optional) Hilt navigation / work manager helpers
    // implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    // kapt("androidx.hilt:hilt-compiler:1.2.0")
}

kapt {
    correctErrorTypes = true
}