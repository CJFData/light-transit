package com.thelightphone.sdk

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LightVibratorTest {
    @Test
    fun pulseWidthTimingsStartOffThenAlternateOnOff() {
        val waveform = LightVibrationWaveform(
            timingsMs = longArrayOf(100L, 100L, 100L),
            amplitudes = intArrayOf(255, 51, 0),
        )

        assertContentEquals(
            longArrayOf(0L, 100L, 0L, 20L, 80L, 0L, 100L),
            pulseWidthTimings(waveform),
        )
    }

    @Test
    fun pulseWidthTimingsKeepTotalDuration() {
        val waveform = LightVibrationWaveform(
            timingsMs = longArrayOf(50L, 70L, 30L),
            amplitudes = intArrayOf(200, 99, 7),
        )

        assertEquals(waveform.durationMs, pulseWidthTimings(waveform).sum())
    }

    @Test
    fun waveformRejectsMismatchedArrays() {
        assertFailsWith<IllegalArgumentException> {
            LightVibrationWaveform(longArrayOf(10L, 10L), intArrayOf(255))
        }
    }

    @Test
    fun waveformRejectsOutOfRangeAmplitude() {
        assertFailsWith<IllegalArgumentException> {
            LightVibrationWaveform(longArrayOf(10L), intArrayOf(256))
        }
    }
}
