pluginManagement {
    // The Gradle plugin `ru.corlex.symbols` is built from source, so the example uses what is in this repo.
    includeBuild("gradle-plugin")
    repositories {
        google()
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

rootProject.name = "corlex-android"

include(":corlex", ":corlex-compose", ":example")
