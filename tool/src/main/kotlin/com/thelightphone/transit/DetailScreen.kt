package com.thelightphone.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.lightClickable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TimezoneResponse(
    val timeZone: String = "",
    val currentLocalTime: String = "",
)

class DetailViewModel : LightViewModel<Unit>() {
    sealed class State {
        object Loading : State()
        data class Time(val timeToDisplay: String) : State()
        data class Error(val message: String) : State()
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        _state.value = State.Loading
        viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            _state.value = try {
                val response: TimezoneResponse = client
                    .get("https://timeapi.io/api/timezone/zone?timeZone=Europe/London")
                    .body()
                State.Time(response.currentLocalTime)
            } catch (e: Exception) {
                State.Error(e.message ?: "Unknown error")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        client.close()
    }
}

class DetailScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, DetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<DetailViewModel>
        get() = DetailViewModel::class.java

    override fun createViewModel() = DetailViewModel()

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(32.dp)
        ) {
            Text(
                text = "London Time",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = when (val s = state) {
                    is DetailViewModel.State.Loading -> "Loading..."
                    is DetailViewModel.State.Time -> s.timeToDisplay
                    is DetailViewModel.State.Error -> "Error: ${s.message}"
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "Go Back",
                modifier = Modifier
                    .padding(top = 16.dp)
                    .lightClickable { goBack() },
            )
        }
    }
}
