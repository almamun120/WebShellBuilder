plugins {
    id("com.android.application")
}

android {
    namespace = "com.webshell.builder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.webshell.builder"
        minSdk = 26
        targetSdk = 34
        // CI can override these: -PversionCode=123 -PversionName=1.2.3
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
    }

    // Release signing comes from environment variables (set by the GitHub workflow).
    val ksFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null && File(ksFile).exists()) {
            create("release") {
                storeFile = File(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falls back to unsigned if no keystore is provided (local builds)
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
        }
    }

    sourceSets {
        getByName("main") {
            // template.apk is copied here by the embedTemplate task below
            assets.srcDir(layout.buildDirectory.dir("generated/templateAssets"))
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.android.tools.build:apksig:8.5.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}

// Build the template app and embed its APK as assets/template.apk inside the builder.
val embedTemplate = tasks.register<Copy>("embedTemplate") {
    dependsOn(":template:assembleRelease")
    from(project(":template").layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "template.apk" }
    }
    into(layout.buildDirectory.dir("generated/templateAssets"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(embedTemplate)
}
