package com.thelightphone.transit.gtfs

/** Initial bearing (degrees clockwise from true north, 0-360) from point 1 to point 2. */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val dLonRad = Math.toRadians(lon2 - lon1)
    val y = kotlin.math.sin(dLonRad) * kotlin.math.cos(lat2Rad)
    val x = kotlin.math.cos(lat1Rad) * kotlin.math.sin(lat2Rad) -
        kotlin.math.sin(lat1Rad) * kotlin.math.cos(lat2Rad) * kotlin.math.cos(dLonRad)
    val bearing = Math.toDegrees(kotlin.math.atan2(y, x))
    return (bearing + 360) % 360
}
