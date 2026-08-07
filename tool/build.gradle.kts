plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        create("lightsdkDev") {
            storeFile = file("../sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))
    // RIPTA's realtime feeds are HTTP-only; netconfig scopes the exception to that host.
    implementation(project(":netconfig"))
    // Only the "org.jetbrains.kotlinx:kotlinx-serialization" prefix is on the SDK plugin's
    // dependency allow-list, but that check is a startsWith match, so this artifact passes too —
    // verified against a live build. No official protobuf/gtfs-realtime-bindings library is
    // allow-listed, hence hand-rolling the small subset of the GTFS-RT schema needed (see
    // GtfsRealtime.kt) rather than pulling one in.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.8.1")
    // Same allow-list prefix as the protobuf artifact above -- used to decode MBTA's V3 API
    // (JSON:API) responses by hand, the same minimal-subset approach as GtfsRealtime.kt's protobuf
    // decoding (see MbtaV3Api.kt).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(libs.kotlin.test)
    ksp(libs.androidx.room.compiler)
}
