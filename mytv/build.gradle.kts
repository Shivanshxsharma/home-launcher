plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.shivansh.mytv"
    compileSdk = 37
    // Pinned so AGP never tries to download its own.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.shivansh.mytv"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // A throwaway development key kept in the repo, so builds from any machine (GitHub's
        // included) can update each other on the box. It signs nothing else.
        create("tv") {
            storeFile = file("tv-debug.keystore")
            storePassword = "android"
            keyAlias = "mytv"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("tv")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Wallpapers are already JPEGs; storing them as they are spares a pass on every read.
        noCompress += "jpg"
    }
}
