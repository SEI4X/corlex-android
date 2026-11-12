plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Uploads mapping.txt after R8 for the minified release build (soft-fails without settings).
    id("ru.corlex.symbols")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Where the example sends its events; override with -Pcorlex.endpoint=... -Pcorlex.key=...
// 10.0.2.2 is the host machine as the emulator sees it. The key below is a placeholder, not a credential.
val corlexKey = providers.gradleProperty("corlex.key").getOrElse("ck_test_exampleexampleexampleexampleexampleexample0")
val corlexEndpoint = providers.gradleProperty("corlex.endpoint").getOrElse("http://10.0.2.2:8787")

android {
    namespace = "ru.corlex.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.corlex.example"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
        buildConfigField("String", "CORLEX_KEY", "\"$corlexKey\"")
        buildConfigField("String", "CORLEX_ENDPOINT", "\"$corlexEndpoint\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The example is installable on an emulator without a keystore; a real app signs with its own.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Uploads need corlex.projectId, corlex.apiUrl and corlex.uploadToken (properties or CORLEX_* env).
// Nothing is committed here.

dependencies {
    implementation(project(":corlex"))
    implementation(project(":corlex-compose"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.navigation.compose)
}
