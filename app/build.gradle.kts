import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Secrets live in the git-ignored local.properties, never in the repo.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
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
        versionCode = 7
        versionName = "0.6.0"
        // A public build (./gradlew assembleRelease -PpublicBuild) carries no key: users connect
        // their own from their phone on the Library tab. Developer builds may bake one in.
        val bakedKey = if (providers.gradleProperty("publicBuild").isPresent) "" else localProps.getProperty("tmdb.apiKey", "")
        buildConfigField("String", "TMDB_API_KEY", "\"$bakedKey\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation(libs.zxing.core)
}
