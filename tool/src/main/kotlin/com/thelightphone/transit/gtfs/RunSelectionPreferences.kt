package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val runSelectionEnabledKey = booleanPreferencesKey("RUN_SELECTION_ENABLED")
private val runStepperEnabledKey = booleanPreferencesKey("RUN_STEPPER_ENABLED")

/**
 * Settings for picking a closest-match run. [runSelectionEnabledFlow] (on by default) shows Trip
 * Detail's Select Run row, which opens the run list. [runStepperEnabledFlow] (off by default, shown
 * only when the first is on) adds Next/Previous icons beside it.
 */
class RunSelectionPreferences(private val dataStore: DataStore<Preferences>) {

    val runSelectionEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[runSelectionEnabledKey] ?: true }

    suspend fun setRunSelectionEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[runSelectionEnabledKey] = enabled }
    }

    val runStepperEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[runStepperEnabledKey] ?: false }

    suspend fun setRunStepperEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[runStepperEnabledKey] = enabled }
    }
}
