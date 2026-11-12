plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

// JitPack builds with JITPACK=true and passes GROUP (com.github.SEI4X.corlex-android) and VERSION (the git
// tag). Anywhere else the coordinates are ru.corlex:<artifact>:0.1.0 (see jitpack.yml and README).
val onJitPack = System.getenv("JITPACK") == "true"
group = if (onJitPack) System.getenv("GROUP") else "ru.corlex"
version = if (onJitPack) System.getenv("VERSION") else "0.1.0"

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Gradle embeds Kotlin 2.0: a plugin must not need anything newer.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Only the API is needed to compile; the app's own AGP is on the classpath at run time.
    compileOnly(libs.agp.api)

    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        create("corlexSymbols") {
            id = "ru.corlex.symbols"
            implementationClass = "ru.corlex.symbols.CorlexSymbolsPlugin"
            displayName = "Corlex symbols"
            description = "Uploads the R8 mapping.txt of release builds to Corlex so crashes are readable."
        }
    }
}

tasks.test {
    useJUnit()
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        name.set("Corlex symbols Gradle plugin")
        description.set("Uploads the R8 mapping.txt of release builds to Corlex so crashes are readable.")
        url.set("https://github.com/SEI4X/corlex-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
    }
}

// JitPack serves the plugin as a plain module (see README: resolutionStrategy); the marker artifact
// under the plugin id would only end up next to it as noise.
if (onJitPack) {
    tasks.matching { it.name.contains("PluginMarkerMaven") }.configureEach { enabled = false }
}
