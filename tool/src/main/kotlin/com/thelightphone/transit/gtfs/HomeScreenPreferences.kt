package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val dailyMessageVisibleKey = booleanPreferencesKey("HOME_DAILY_MESSAGE_VISIBLE")
private val dailyMessageRandomKey = booleanPreferencesKey("HOME_DAILY_MESSAGE_RANDOM")

/** Home screen display settings. */
class HomeScreenPreferences(private val dataStore: DataStore<Preferences>) {

    /** On by default: the daily message under the agency or trip status. */
    val dailyMessageVisibleFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[dailyMessageVisibleKey] ?: true }

    suspend fun setDailyMessageVisible(visible: Boolean) {
        dataStore.edit { prefs -> prefs[dailyMessageVisibleKey] = visible }
    }

    /** Off by default: a new message each visit instead of one per day. */
    val dailyMessageRandomFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[dailyMessageRandomKey] ?: false }

    suspend fun setDailyMessageRandom(random: Boolean) {
        dataStore.edit { prefs -> prefs[dailyMessageRandomKey] = random }
    }
}
