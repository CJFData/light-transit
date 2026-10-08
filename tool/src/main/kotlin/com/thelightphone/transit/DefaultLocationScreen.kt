package com.thelightphone.transit

import android.Manifest
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.transit.gtfs.GeocodeResult
import com.thelightphone.transit.gtfs.LocationPreferences
import com.thelightphone.transit.gtfs.NominatimGeocoder
import com.thelightphone.transit.gtfs.SavedLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val PERMISSION_CHECK_TIMEOUT_MS = 2_000L

sealed class DefaultLocationMode {
    object Input : DefaultLocationMode()
    object Working : DefaultLocationMode()
    data class Results(val results: List<GeocodeResult>) : DefaultLocationMode()
    data class Error(val message: String) : DefaultLocationMode()
    /** Saved; the screen closes. */
    object Saved : DefaultLocationMode()
}

class DefaultLocationViewModel(private val locationPreferences: LocationPreferences) : LightViewModel<Unit>() {

    private val geocoder = NominatimGeocoder()

    private val _mode = MutableStateFlow<DefaultLocationMode>(DefaultLocationMode.Input)
    val mode: StateFlow<DefaultLocationMode> = _mode

    /** Whether "Use current location" is offered. */
    val locationEnabled: StateFlow<Boolean>
        get() = _locationEnabled
    private val _locationEnabled = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            locationPreferences.locationEnabledFlow.collect { _locationEnabled.value = it }
        }
    }

    fun search(query: CharSequence) {
        val text = query.toString().trim()
        if (text.isEmpty()) return
        _mode.value = DefaultLocationMode.Working
        viewModelScope.launch(Dispatchers.IO) {
            _mode.value = try {
                val results = geocoder.search(text)
                if (results.isEmpty()) DefaultLocationMode.Error("No matching location found.")
                else DefaultLocationMode.Results(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DefaultLocationScreen", "Geocoding failed for '$text'", e)
                DefaultLocationMode.Error("Unable to search that location.")
            }
        }
    }

    fun choose(result: GeocodeResult) = save(SavedLocation(result.lat, result.lon, result.displayName))

    /**
     * Makes current location the default, so Explore finds you by GPS each time. Asks for
     * permission if it's never been asked.
     */
    fun useCurrentLocation(requestPermission: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            locationPreferences.setDefaultToCurrentLocation()
            // Saved first, so a slow or missing LightOS answer can't block it.
            val permission = withTimeoutOrNull(PERMISSION_CHECK_TIMEOUT_MS) {
                checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            }?.getOrNull()?.permissionResult
            if (permission == LightServiceMethod.GetPermission.Result.Unknown) {
                withContext(Dispatchers.Main) { requestPermission() }
            }
            _mode.value = DefaultLocationMode.Saved
        }
    }

    fun backToInput() {
        _mode.value = DefaultLocationMode.Input
    }

    private fun save(location: SavedLocation) {
        viewModelScope.launch(Dispatchers.IO) {
            locationPreferences.setDefaultLocation(location)
            _mode.value = DefaultLocationMode.Saved
        }
    }

    override fun onCleared() {
        super.onCleared()
        geocoder.close()
    }
}

/** Sets the default location: a searched address, or current location when location is on. */
class DefaultLocationScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, DefaultLocationViewModel>(sealedActivity) {

    override val viewModelClass: Class<DefaultLocationViewModel>
        get() = DefaultLocationViewModel::class.java

    override fun createViewModel(): DefaultLocationViewModel =
        DefaultLocationViewModel(LocationPreferences(lightContext.dataStore))

    @Composable
    override fun Content() {
        val mode by viewModel.mode.collectAsState()
        val locationEnabled by viewModel.locationEnabled.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val locationPermissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.ACCESS_FINE_LOCATION)
        // Hoisted so the keyboard's callback keeps the same state across mode changes.
        val textFieldState = rememberTextFieldState("")
        val keyboardCallback = remember(textFieldState) {
            InlineTextFieldKeyboardCallback(state = textFieldState, onReturn = { viewModel.search(textFieldState.text) })
        }

        LaunchedEffect(mode) {
            if (mode is DefaultLocationMode.Saved) goBack()
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Default Location"),
                )
                when (val m = mode) {
                    is DefaultLocationMode.Input -> {
                        val keyboardViewModel: Lp3KeyboardViewModel<*> = rememberInlineLp3KeyboardViewModel(
                            key = "DefaultLocationKeyboard",
                            callback = keyboardCallback,
                            keyboardOptionsFlow = keyboardOptionsFlow,
                        )
                        Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
                            BasicText(
                                text = textFieldState.text.toString(),
                                style = LightThemeTokens.typography.copy.copy(color = LightThemeTokens.colors.content),
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(LightThemeTokens.colors.content),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .lightClickable { viewModel.search(textFieldState.text) }
                                .padding(horizontal = 32.dp),
                        ) {
                            LightIcon(
                                icon = LightIcons.SEARCH,
                                size = 1.2f,
                                contentDescription = "Search",
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            LightText(text = "Search", variant = LightTextVariant.Copy, lighten = true)
                        }
                        // Only offered while location is on.
                        if (locationEnabled) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .lightClickable {
                                        viewModel.useCurrentLocation(requestPermission = { locationPermissionLauncher?.launch() })
                                    }
                                    .padding(horizontal = 32.dp, vertical = 8.dp),
                            ) {
                                LightIcon(
                                    icon = LightIcons.CROSSHAIR,
                                    size = 1.2f,
                                    contentDescription = "Use current location",
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                LightText(text = "Use current location", variant = LightTextVariant.Copy, lighten = true)
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)
                    }

                    is DefaultLocationMode.Working, is DefaultLocationMode.Saved -> LightText(
                        text = "Finding location...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(32.dp),
                    )

                    is DefaultLocationMode.Error -> Column(modifier = Modifier.padding(32.dp)) {
                        LightText(
                            text = m.message,
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        LightText(
                            text = "Try Again",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.lightClickable { viewModel.backToInput() },
                        )
                    }

                    is DefaultLocationMode.Results -> Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(m.results) { result ->
                                LightText(
                                    text = result.displayName,
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable { viewModel.choose(result) }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                        LightText(
                            text = "Location search © OpenStreetMap contributors",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
