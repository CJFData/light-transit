package com.thelightphone.sdk

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class LightVibratorTest {
    @Test
    fun pulseWidthTimingsStartOffThenAlternateOnOff() {
        val waveform = LightVibrationWaveform(
            durations = arrayOf(100.milliseconds, 100.milliseconds, 100.milliseconds),
            amplitudes = arrayOf(VibrationAmplitude(255), VibrationAmplitude(51), VibrationAmplitude(0)),
        )

        assertContentEquals(
            longArrayOf(0L, 100L, 0L, 20L, 80L, 0L, 100L),
            pulseWidthTimings(waveform),
        )
    }

    @Test
    fun pulseWidthTimingsKeepTotalDuration() {
        val waveform = LightVibrationWaveform(
            durations = arrayOf(50.milliseconds, 70.milliseconds, 30.milliseconds),
            amplitudes = arrayOf(VibrationAmplitude(200), VibrationAmplitude(99), VibrationAmplitude(7)),
        )

        assertEquals(waveform.duration.inWholeMilliseconds, pulseWidthTimings(waveform).sum())
    }

    @Test
    fun waveformRejectsMismatchedArrays() {
        assertFailsWith<IllegalArgumentException> {
            LightVibrationWaveform(
                arrayOf(10.milliseconds, 10.milliseconds),
                arrayOf(VibrationAmplitude(255)),
            )
        }
    }

    @Test
    fun amplitudeRejectsOutOfRangeValue() {
        assertFailsWith<IllegalArgumentException> {
            VibrationAmplitude(256)
        }
        assertFailsWith<IllegalArgumentException> {
            VibrationAmplitude(-1)
        }
    }

    @Test
    fun waveformRejectsNegativeDuration() {
        assertFailsWith<IllegalArgumentException> {
            LightVibrationWaveform(arrayOf((-1).milliseconds), arrayOf(VibrationAmplitude(255)))
        }
    }
}
