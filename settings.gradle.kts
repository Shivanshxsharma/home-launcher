pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mibox-launcher"
include(":app")
// A second launcher with a different layout; shares the Home launcher's data code (see maximal/build.gradle.kts).
include(":maximal")
// A third: black and bare, only a clock, your apps and your favorites (see minimal/build.gradle.kts).
include(":minimal")
// A fourth: the lightest, a wallpaper, a clock and your favorites, no network (see mytv/build.gradle.kts).
include(":mytv")
