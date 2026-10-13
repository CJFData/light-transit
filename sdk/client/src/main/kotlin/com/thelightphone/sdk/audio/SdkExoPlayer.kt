package com.thelightphone.sdk.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds the player the SDK owns, in the one place both playback modes reach.
 *
 * A requested cache wraps HTTP only; [DefaultDataSource] wraps it so local
 * files are not copied into the cache.
 */
@OptIn(UnstableApi::class)
internal fun buildSdkExoPlayer(
    context: Context,
    usage: LightAudioUsage,
    cache: LightMediaCache?,
): ExoPlayer {
    val builder = ExoPlayer.Builder(context)
    if (cache != null) {
        val network = CacheDataSource.Factory()
            .setCache(mediaCacheStore.open(context, cache))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        builder.setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, network))
        )
    }
    return builder.build().apply {
        setAudioAttributes(usage.toMedia3AudioAttributes(), true)
    }
}

/**
 * One [SimpleCache] per directory per process. Opening is atomic because an
 * attached player and [LightAudioService] can reach for the same cache at once.
 */
@OptIn(UnstableApi::class)
internal class MediaCacheStore {
    private val cachesByPath = ConcurrentHashMap<String, OpenedCache>()
    private val databaseProviders = ConcurrentHashMap<Context, DatabaseProvider>()

    fun open(context: Context, spec: LightMediaCache): Cache {
        val appContext = context.applicationContext
        val directory = spec.directoryIn(appContext)
        val opened = cachesByPath.computeIfAbsent(directory.canonicalPath) {
            OpenedCache(
                SimpleCache(directory, LeastRecentlyUsedCacheEvictor(spec.sizeBytes), databaseProvider(appContext)),
                spec.sizeBytes,
            )
        }
        requireSameCacheSize(spec, opened.sizeBytes)
        return opened.cache
    }

    private fun databaseProvider(appContext: Context): DatabaseProvider =
        databaseProviders.computeIfAbsent(appContext) { StandaloneDatabaseProvider(it) }
}

private class OpenedCache(val cache: Cache, val sizeBytes: Long)

/** A cache's size is fixed by its first opening in the process. */
internal fun requireSameCacheSize(requested: LightMediaCache, openedSizeBytes: Long) {
    require(requested.sizeBytes == openedSizeBytes) {
        "Cache '${requested.name}' is already open with $openedSizeBytes bytes; " +
            "requested ${requested.sizeBytes}"
    }
}

internal val mediaCacheStore = MediaCacheStore()

private fun LightMediaCache.directoryIn(context: Context): File =
    File(File(context.cacheDir, CACHE_ROOT_DIR), name).apply { mkdirs() }

private const val CACHE_ROOT_DIR = "light-media-cache"
