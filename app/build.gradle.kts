plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.shivansh.mibox"
    compileSdk = 37
    // Pinned to the installed build-tools so AGP never tries to download its own.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.shivansh.mibox"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded onto the user's own box, so the debug key is fine for now.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Preview clips are played straight from the APK, which needs them stored uncompressed.
        noCompress += "mp4"
    }
}

dependencies {
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.recyclerview)
}
