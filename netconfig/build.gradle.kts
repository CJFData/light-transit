// --- BEGIN removable cleartext exception for RIPTA/LTC realtime feeds ---
//
// This module exists solely to grant realtime.ripta.com and gtfs.ltconline.ca a Network Security
// Config cleartext exception (see src/main/res/xml/network_security_config.xml). RIPTA's and LTC
// London's realtime TripUpdates/VehiclePositions feeds are served plain-HTTP-only with no HTTPS
// equivalent, and Android blocks cleartext traffic by default. (LTC's separate static-feed
// trust-anchor issue is unrelated and does NOT live here -- see GtfsTrustAnchors.kt in :tool for
// why that one is deliberately a code-only fix instead.)
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
// --- END removable cleartext exception for RIPTA/LTC realtime feeds ---
