plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.kabellosan.p3dj"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kabellosan.p3dj"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("VERSION_CODE") ?: "1")
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("P3DJ_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("P3DJ_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("P3DJ_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("P3DJ_KEY_PASSWORD") ?: System.getenv("P3DJ_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("P3DJ_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
}
