package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val dailyMessageVisibleKey = booleanPreferencesKey("HOME_DAILY_MESSAGE_VISIBLE")
private val dailyMessageRandomKey = booleanPreferencesKey("HOME_DAILY_MESSAGE_RANDOM")

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

    /** Off by default -- preserves the original one-message-per-calendar-day design (see
     * DAILY_MESSAGES' own doc comment in HomeScreen.kt) unless a rider opts into a fresh random
     * message every time they return to the home screen instead. */
    val dailyMessageRandomFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[dailyMessageRandomKey] ?: false }

    suspend fun setDailyMessageRandom(random: Boolean) {
        dataStore.edit { prefs -> prefs[dailyMessageRandomKey] = random }
    }
}
