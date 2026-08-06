// --- BEGIN removable cleartext exception for RIPTA realtime feeds ---
//
// This module exists solely to grant realtime.ripta.com a Network Security Config cleartext
// exception (see src/main/res/xml/network_security_config.xml). RIPTA's realtime TripUpdates/
// VehiclePositions feeds are served plain-HTTP-only with no HTTPS equivalent, and Android blocks
// cleartext traffic by default.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.thelightphone.netconfig"
    compileSdk = rootProject.ext["compileSdk"] as Int

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
    }
}
// --- END removable cleartext exception for RIPTA realtime feeds ---
