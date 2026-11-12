plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// JitPack builds with JITPACK=true and passes GROUP (com.github.SEI4X.corlex-android) and VERSION (the git
// tag). Anywhere else the coordinates are ru.corlex:<artifact>:0.1.0 (see jitpack.yml and README).
val onJitPack = System.getenv("JITPACK") == "true"
group = if (onJitPack) System.getenv("GROUP") else "ru.corlex"
version = if (onJitPack) System.getenv("VERSION") else "0.1.0"

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

android {
    namespace = "ru.corlex.compose"
    compileSdk = 35

    defaultConfig { minSdk = 23 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":corlex"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.navigation.testing)
}

afterEvaluate {
    publishing.publications.create<MavenPublication>("release") {
        from(components["release"])
        artifactId = "corlex-compose"
        pom {
            name.set("Corlex Compose")
            description.set("Screen tracking for Navigation Compose")
            url.set("https://github.com/SEI4X/corlex-android")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
        }
    }
}
