package com.thelightphone.sdk

import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Why the tool vibrates, so the platform applies the matching user settings. */
enum class LightVibrationUsage {
    /** Feedback for a direct touch. */
    Touch,
    /** An alarm or timer the user set. Vibrates while the screen is off. */
    Alarm,
}

/**
 * Segment `i` lasts `timingsMs[i]` milliseconds at intensity `amplitudes[i]`,
 * where `0` is off and `255` is the motor's maximum.
 */
class LightVibrationWaveform(
    val timingsMs: LongArray,
    val amplitudes: IntArray,
) {
    init {
        require(timingsMs.size == amplitudes.size) { "Each timing needs one amplitude" }
        require(timingsMs.all { it >= 0L }) { "Timings must not be negative" }
        require(amplitudes.all { it in 0..MAX_AMPLITUDE }) { "Amplitudes must be in 0..$MAX_AMPLITUDE" }
    }

    val durationMs: Long get() = timingsMs.sum()

    companion object {
        const val MAX_AMPLITUDE = 255
    }
}

interface LightVibrator {
    /**
     * Whether the motor can vary its intensity. Without it, waveforms are
     * approximated by on/off pulses whose on-time is proportional to amplitude.
     */
    val hasAmplitudeControl: Boolean
    fun vibrate(waveform: LightVibrationWaveform, usage: LightVibrationUsage = LightVibrationUsage.Touch)
    fun cancel()
}

/**
 * Holds only the application context, so it may outlive the screen that
 * created it. Does nothing on devices without a vibrator.
 */
class DefaultLightVibrator(sealedActivity: SealedLightActivity) : LightVibrator {
    private val vibrator: Vibrator? = sealedActivity.activity.applicationContext
        .getSystemService(VibratorManager::class.java)
        ?.defaultVibrator
        ?.takeIf { it.hasVibrator() }

    override val hasAmplitudeControl: Boolean
        get() = vibrator?.hasAmplitudeControl() == true

    override fun vibrate(waveform: LightVibrationWaveform, usage: LightVibrationUsage) {
        val vibrator = vibrator ?: return
        if (waveform.durationMs <= 0L) return
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(waveform.timingsMs, waveform.amplitudes, NO_REPEAT)
        } else {
            VibrationEffect.createWaveform(pulseWidthTimings(waveform), NO_REPEAT)
        }
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage.toPlatformUsage()))
    }

    override fun cancel() {
        vibrator?.cancel()
    }

    private companion object {
        const val NO_REPEAT = -1
    }
}

private fun LightVibrationUsage.toPlatformUsage(): Int = when (this) {
    LightVibrationUsage.Touch -> VibrationAttributes.USAGE_TOUCH
    LightVibrationUsage.Alarm -> VibrationAttributes.USAGE_ALARM
}

/**
 * On/off timings for [VibrationEffect.createWaveform] without amplitudes:
 * alternating off/on durations, starting with off. Each segment is on for
 * the fraction of its duration given by its amplitude, then off.
 */
internal fun pulseWidthTimings(waveform: LightVibrationWaveform): LongArray {
    val timings = mutableListOf(0L)
    waveform.timingsMs.forEachIndexed { index, durationMs ->
        val onMs = durationMs * waveform.amplitudes[index] / LightVibrationWaveform.MAX_AMPLITUDE
        timings += onMs
        timings += durationMs - onMs
    }
    return timings.toLongArray()
}
