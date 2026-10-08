package com.thelightphone.sdk

import kotlin.time.Duration

/** Holds only the application context, so it may outlive the screen that created it. */
class DefaultLightVibrator(sealedActivity: SealedLightActivity) : LightVibrator {
    private val delegate = ContextLightVibrator(sealedActivity.activity.applicationContext)

    override val hasAmplitudeControl: Boolean get() = delegate.hasAmplitudeControl

    override fun click() = delegate.click()

    override fun vibrateForDuration(duration: Duration) = delegate.vibrateForDuration(duration)

    override fun vibrate(waveform: LightVibrationWaveform, usage: LightVibrationUsage) =
        delegate.vibrate(waveform, usage)

    override fun cancel() = delegate.cancel()
}
