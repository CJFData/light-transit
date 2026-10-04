package com.thelightphone.transit

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.transit.gtfs.AgencyPreferences
import com.thelightphone.transit.gtfs.AlertPreferences
import com.thelightphone.transit.gtfs.BoardedTripPreferences
import com.thelightphone.transit.gtfs.decidePopups
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CHECK_INTERVAL_MS = 60_000L

/**
 * Pops up new alerts while the app is in the foreground, on any screen. Only alerts the home screen
 * would show are considered (see [homeScreenAlerts]), and only while "Pop up new alerts" is on.
 * Nothing runs in the background.
 */
object AlertPopups {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installed = false
    private var job: Job? = null

    /** Starts watching for the app coming to the foreground. Safe to call more than once. */
    fun install(dataStore: DataStore<Preferences>, filesDir: File) {
        if (installed) return
        installed = true
        val alertPreferences = AlertPreferences(dataStore)
        val agencyPreferences = AgencyPreferences(dataStore)
        val boardedTripPreferences = BoardedTripPreferences(dataStore)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                job?.cancel()
                job = scope.launch {
                    alertPreferences.popUpFlow.collectLatest { popUp ->
                        while (popUp && isActive) {
                            check(filesDir, alertPreferences, agencyPreferences, boardedTripPreferences)
                            delay(CHECK_INTERVAL_MS)
                        }
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                job?.cancel()
                job = null
            }
        })
    }

    private suspend fun check(
        filesDir: File,
        alertPreferences: AlertPreferences,
        agencyPreferences: AgencyPreferences,
        boardedTripPreferences: BoardedTripPreferences,
    ) {
        try {
            val agency = agencyPreferences.defaultAgencyFlow.first() ?: return
            val boardedTrip = boardedTripPreferences.boardedTripFlow.first()
            val (candidates, screenAlerts) =
                homeScreenAlerts(agency, filesDir, alertPreferences, boardedTrip, AlertSurface.POPUP) ?: return
            val decision = decidePopups(agency.id, candidates, screenAlerts.index.feedIds, alertPreferences.seenFlow.first())
            // Saved before showing, so a dismissed pop-up doesn't come back.
            alertPreferences.setSeen(decision.seen)
            if (decision.toShow.isEmpty()) return
            // Never replaces a modal that's already open.
            LightModalManager.activeModal.first { it == null }
            withContext(Dispatchers.Main) { showAlerts(decision.toShow, screenAlerts) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AlertPopups", "Alert pop-up check failed", e)
        }
    }
}
