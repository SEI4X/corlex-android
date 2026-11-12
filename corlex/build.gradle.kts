plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    namespace = "ru.corlex"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${project.version.toString().removePrefix("v")}\"")
    }

    buildFeatures { buildConfig = true }

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
    // WorkManager delivers the queue when the app is in the background and the network is back.
    implementation(libs.androidx.work)

    testImplementation(libs.junit)
    testImplementation(libs.json) // android.jar only has stubs of org.json on the JVM
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

afterEvaluate {
    publishing.publications.create<MavenPublication>("release") {
        from(components["release"])
        artifactId = "corlex"
        pom {
            name.set("Corlex SDK for Android")
            description.set("Corlex analytics: events, screens, crashes, Remote Config and A/B tests")
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
