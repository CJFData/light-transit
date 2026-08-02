package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val dailyMessageVisibleKey = booleanPreferencesKey("HOME_DAILY_MESSAGE_VISIBLE")

/**
 * HomeScreen's own display settings (Settings screen), persisted the same way [MapPreferences]
 * persists the Map screen's -- a separate class rather than folding into an existing one since
 * this isn't about the map, a boarded trip, or agency selection, the concerns those classes
 * already own.
 */
class HomeScreenPreferences(private val dataStore: DataStore<Preferences>) {

    /** On by default -- the daily rotating message under the agency list/trip status. */
    val dailyMessageVisibleFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[dailyMessageVisibleKey] ?: true }

    suspend fun setDailyMessageVisible(visible: Boolean) {
        dataStore.edit { prefs -> prefs[dailyMessageVisibleKey] = visible }
    }
}
