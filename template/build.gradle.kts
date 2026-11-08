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
// NOTE: no androidx / third-party dependencies on purpose. Libraries would add
// extra manifest entries (permissions, providers) that break repackaging.
