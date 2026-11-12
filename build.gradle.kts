plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// `./gradlew check` also runs the tests of the included build with the Gradle plugin.
tasks.register("check") {
    group = "verification"
    description = "Unit tests of the SDK, the Compose module and the Gradle plugin."
    dependsOn(gradle.includedBuild("gradle-plugin").task(":test"))
    dependsOn(":corlex:testDebugUnitTest", ":corlex-compose:testDebugUnitTest")
}

// Source releases must resolve the reviewed dependency graph, including runtime transitive versions.
allprojects {
    dependencyLocking { lockAllConfigurations() }
}
