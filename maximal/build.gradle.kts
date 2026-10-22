import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

// Secrets live in the git-ignored local.properties, never in the repo.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

/**
 * Brings in the Home launcher's data code (TMDB client and catalog, the connect-from-phone page,
 * installed apps, demo content) exactly as it is, so both launchers run on one copy of it. The
 * files are read from app/ and copied into build/, never edited.
 */
abstract class ShareHomeData @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun share() {
        fs.sync {
            from(source)
            into(outputDir.dir("com/shivansh/mibox/data"))
        }
    }
}

val shareHomeData = tasks.register<ShareHomeData>("shareHomeData") {
    source.set(rootProject.layout.projectDirectory.dir("app/src/main/java/com/shivansh/mibox/data"))
}

android {
    namespace = "com.shivansh.mibox.maximal"
    compileSdk = 37
    // Pinned to the installed build-tools so AGP never tries to download its own.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.shivansh.mibox.maximal"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        // Same rule as the Home launcher: a public build (-PpublicBuild) carries no key, and users
        // connect their own from their phone; developer builds may bake one in.
        val bakedKey = if (providers.gradleProperty("publicBuild").isPresent) "" else localProps.getProperty("tmdb.apiKey", "")
        buildConfigField("String", "TMDB_API_KEY", "\"$bakedKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        // The Home launcher's demo shows and preview clips, shared rather than copied.
        getByName("main").assets.directories.add(rootProject.file("app/src/main/assets").path)
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

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(shareHomeData, ShareHomeData::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.recyclerview)
    implementation(libs.zxing.core)
}
