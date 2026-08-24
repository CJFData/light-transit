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
 * Settings for a rider's own way to override a [FuzzyRunTrips] boarded trip's automatic run match --
 * a nested pair, not two independent switches. [runSelectionEnabledFlow] (on by default) gates the
 * whole "Select Run" row on Trip Detail: tapping the label always opens the full list (tap a vehicle
 * marker directly on the trip's own stop list). [runStepperEnabledFlow] (off by default) is a second,
 * finer toggle nested under the first -- Settings itself only shows this row at all while run
 * selection is on, since a stepper with nothing to step through makes no sense otherwise -- that
 * additionally shows Next/Previous run icons flanking the same label for a fast one-tap nudge,
 * without needing to open the list screen.
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
