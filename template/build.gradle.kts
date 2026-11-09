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
}
// NOTE: no androidx / other third-party UI libraries on purpose. Libraries would add
// extra manifest entries (permissions, providers) that break repackaging.
