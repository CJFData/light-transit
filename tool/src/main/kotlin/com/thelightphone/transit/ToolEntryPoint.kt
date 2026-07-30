package com.thelightphone.transit

import android.util.Log
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    // called when Tool first launches, use to initialize dependencies etc
    override suspend fun onToolCreate(
        serverData: StateFlow<LightServerData?>,
    ) {
        serverData.collect {
            // this is where you'd send push credentials up to your app server
            Log.d("ToolEntryPoint", "Current LightOS registration data: $it")
        }
    }

    override suspend fun onPushNotification(
        data: ByteArray,
    ) {
        Log.d("ToolEntryPoint", "received push notification: $data")
    }
}