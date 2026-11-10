plugins {
    id("com.android.application")
}

android {
    namespace = "com.webshell.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.webshell.template"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.0-template"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
dependencies {
    // Pure-Java barcode decoder for Android.scanQR(). It ships no manifest entries or resources,
    // so repackaging the APK is not affected.
    implementation("com.google.zxing:core:3.5.3")

    // ExoPlayer (Media3) for the built-in media player: HLS, DASH, live streams, track selection.
    // Only the core / HLS / DASH modules are used (no media3-ui, no media3-session), so they add no
    // activities, services or providers. The only manifest entry that may come in transitively is the
    // androidx.core "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"; the builder keeps and renames it.
    val media3 = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
}
