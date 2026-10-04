package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val alertsEnabledKey = booleanPreferencesKey("ALERTS_ENABLED")
private val alertsOnHomeScreenKey = booleanPreferencesKey("ALERTS_ON_HOME_SCREEN")
private val alertsBoardedOnlyKey = booleanPreferencesKey("ALERTS_BOARDED_TRIPS_ONLY")
private val alertsPopUpKey = booleanPreferencesKey("ALERTS_POP_UP_NEW")
private val alertsSeenKey = stringSetPreferencesKey("ALERTS_SEEN_VERSIONS")

/** Alert settings. Everything is off until the master toggle is turned on. */
class AlertPreferences(private val dataStore: DataStore<Preferences>) {

    val enabledFlow: Flow<Boolean> = dataStore.data.map { it[alertsEnabledKey] ?: false }
    val onHomeScreenFlow: Flow<Boolean> = dataStore.data.map { it[alertsOnHomeScreenKey] ?: true }
    val boardedOnlyFlow: Flow<Boolean> = dataStore.data.map { it[alertsBoardedOnlyKey] ?: false }
    val popUpFlow: Flow<Boolean> = dataStore.data.map { it[alertsPopUpKey] ?: false }

    /** Seen alert versions, as "agencyId:alertId" to version. */
    val seenFlow: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        prefs[alertsSeenKey].orEmpty().mapNotNull { entry ->
            val split = entry.lastIndexOf('=')
            if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
        }.toMap()
    }

    suspend fun setEnabled(enabled: Boolean) = dataStore.edit { it[alertsEnabledKey] = enabled }
    suspend fun setOnHomeScreen(enabled: Boolean) = dataStore.edit { it[alertsOnHomeScreenKey] = enabled }
    suspend fun setBoardedOnly(enabled: Boolean) = dataStore.edit { it[alertsBoardedOnlyKey] = enabled }
    suspend fun setPopUp(enabled: Boolean) = dataStore.edit { it[alertsPopUpKey] = enabled }

    suspend fun setSeen(seen: Map<String, String>) =
        dataStore.edit { prefs -> prefs[alertsSeenKey] = seen.map { (key, version) -> "$key=$version" }.toSet() }
}
