package com.thelightphone.sdk.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val streamCache = LightMediaCache(name = "stream", sizeBytes = 256L * 1024 * 1024)

class LightMediaCacheTest {
    @Test
    fun cacheNameHasToBeUsableAsADirectoryName() {
        assertFailsWith<IllegalArgumentException> { LightMediaCache(" ", SIZE) }
        assertFailsWith<IllegalArgumentException> { LightMediaCache("../escape", SIZE) }
        assertFailsWith<IllegalArgumentException> { LightMediaCache("nested/name", SIZE) }
        assertEquals("tidal_stream-1", LightMediaCache("tidal_stream-1", SIZE).name)
    }

    @Test
    fun cacheNeedsRoomToWorkWith() {
        assertFailsWith<IllegalArgumentException> { LightMediaCache("stream", 0) }
        assertFailsWith<IllegalArgumentException> { LightMediaCache("stream", -1) }
    }

    @Test
    fun reopeningANameRequiresTheSameSize() {
        requireSameCacheSize(LightMediaCache("stream", SIZE), openedSizeBytes = SIZE)
        assertFailsWith<IllegalArgumentException> {
            requireSameCacheSize(LightMediaCache("stream", SIZE * 2), openedSizeBytes = SIZE)
        }
    }

    private companion object {
        const val SIZE = 1024L
    }
}

class DetachedSessionCacheTest {
    private val detachedSpec = LightPlayerSpec(
        playback = LightAudioPlayback.Detached,
        cache = streamCache,
    )

    @Test
    fun specCompatibilityAcceptsFreshAndMatchingSessionsOnly() {
        assertTrue(isDetachedSpecCompatible(null, detachedSpec))
        assertTrue(isDetachedSpecCompatible(detachedSpec, detachedSpec.copy()))
        assertFalse(isDetachedSpecCompatible(detachedSpec, detachedSpec.copy(cache = streamCache.copy(name = "other"))))
        assertFalse(isDetachedSpecCompatible(detachedSpec, detachedSpec.copy(usage = LightAudioUsage.Alarm)))
    }

    @Test
    fun stagedSpecSurvivesServiceAdoptionAndClearsWithSession() {
        val state = DetachedSessionState()
        state.stageSpec(detachedSpec)
        assertEquals(detachedSpec, state.stagedSpec())
        state.adoptSpec(state.stagedSpec())
        assertEquals(detachedSpec, state.activeSpec())
        state.clearSession()
        assertNull(state.activeSpec())
    }
}
