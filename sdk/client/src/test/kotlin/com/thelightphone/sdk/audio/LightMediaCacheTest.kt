package com.thelightphone.sdk.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val streamCache = LightMediaCache(
    name = "stream",
    eviction = LightCacheEviction.LeastRecentlyUsed(maxBytes = 256L * 1024 * 1024),
)
private val savedCache = LightMediaCache(name = "saved", eviction = LightCacheEviction.Never)

class LightMediaCacheTest {
    @Test
    fun cacheNameHasToBeUsableAsADirectoryName() {
        assertFailsWith<IllegalArgumentException> {
            LightMediaCache(" ", LightCacheEviction.Never)
        }
        assertFailsWith<IllegalArgumentException> {
            LightMediaCache("../escape", LightCacheEviction.Never)
        }
        assertFailsWith<IllegalArgumentException> {
            LightMediaCache("nested/name", LightCacheEviction.Never)
        }
        assertEquals("tidal_stream-1", LightMediaCache("tidal_stream-1", LightCacheEviction.Never).name)
    }

    @Test
    fun sizeLimitedCacheNeedsRoomToWorkWith() {
        assertFailsWith<IllegalArgumentException> { LightCacheEviction.LeastRecentlyUsed(0) }
        assertFailsWith<IllegalArgumentException> { LightCacheEviction.LeastRecentlyUsed(-1) }
    }

    @Test
    fun readThroughOrderAcceptsOneWritableCacheAheadOfSavedOnes() {
        validateMediaCaches(emptyList())
        validateMediaCaches(listOf(streamCache, savedCache))
        validateMediaCaches(listOf(savedCache))
    }

    @Test
    fun cachesCannotShareANameOrBothClaimTheStreamedBytes() {
        assertFailsWith<IllegalArgumentException> {
            validateMediaCaches(listOf(savedCache, savedCache))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMediaCaches(
                listOf(streamCache, streamCache.copy(name = "other")),
            )
        }
    }

    @Test
    fun distinctNamesRemainRequired() {
        assertFailsWith<IllegalArgumentException> {
            validateMediaCaches(listOf(savedCache, savedCache))
        }
    }
}

class DetachedSessionCacheTest {
    private val detachedSpec = LightPlayerSpec(
        playback = LightAudioPlayback.Detached,
        caches = listOf(streamCache),
    )

    @Test
    fun specCompatibilityAcceptsFreshAndMatchingSessionsOnly() {
        assertTrue(isDetachedSpecCompatible(null, detachedSpec))
        assertTrue(isDetachedSpecCompatible(detachedSpec, detachedSpec.copy()))
        assertFalse(isDetachedSpecCompatible(detachedSpec, detachedSpec.copy(caches = listOf(savedCache))))
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
