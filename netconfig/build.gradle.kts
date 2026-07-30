// --- BEGIN removable cleartext exception for RIPTA realtime feeds ---
//
// This module exists solely to grant realtime.ripta.com a Network Security Config cleartext
// exception (see src/main/res/xml/network_security_config.xml). RIPTA's realtime TripUpdates/
// VehiclePositions feeds are served plain-HTTP-only with no HTTPS equivalent, and Android blocks
// cleartext traffic by default — this module's manifest merges the exception into :tool's final
// packaged manifest.
//
// Deliberately does NOT apply the com.thelightphone.light-sdk plugin — that plugin's manifest
// generation has no field for network security config, and hand-editing an
// AndroidManifest.xml in a plugin-applying module is rejected outright. A plain sibling library
// module sidesteps that: the plugin's own dependency validator explicitly exempts same-build
// project dependencies (see LightSdkPlugin.isProjectDependency), and since this module never
// applies the plugin, none of its restrictions apply to it either. Verified against a real forced
// rebuild that the merged attribute survives into :tool's final packaged manifest — confirmed via
// tool/build/intermediates/packaged_manifests/.../AndroidManifest.xml, not just the intermediate
// merge blame log.
//
// TO REMOVE THIS EXCEPTION (restore HTTPS-only enforcement everywhere):
//   1. Delete this module (the netconfig/ directory).
//   2. Remove `include(":netconfig")` from settings.gradle.kts.
//   3. Remove `implementation(project(":netconfig"))` from tool/build.gradle.kts.
//   4. In GtfsAgency.kt, set RIPTA's realtimeTripUpdatesUrl/realtimeVehiclePositionsUrl back to
//      null (the original, HTTPS-only-safe state).
//
// UNVERIFIED: whether Light's official build/signing pipeline (builder/) accepts a sibling module
// built this way — only confirmed against local Gradle builds so far.
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
