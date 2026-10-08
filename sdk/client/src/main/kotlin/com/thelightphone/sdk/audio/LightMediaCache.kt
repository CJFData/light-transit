package com.thelightphone.sdk.audio

/**
 * A named on-disk store of media bytes, kept under the tool's own storage.
 *
 * The SDK opens the store, wires it into the player it builds, and shares one
 * per directory across the process. Bytes are filed under [LightAudioItem.id],
 * so an item whose location changes still finds what it cached.
 *
 * @property name directory name: letters, digits, `-`, and `_` only
 * @property eviction what the store does when it runs out of room
 */
data class LightMediaCache(
    val name: String,
    val eviction: LightCacheEviction,
) {
    init {
        require(name.isNotBlank()) { "Cache name must not be blank" }
        require(name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Cache name must contain only letters, digits, '-', or '_', but was '$name'"
        }
    }
}

/** What a [LightMediaCache] does when it runs out of room. */
sealed interface LightCacheEviction {
    /**
     * Discards the bytes read longest ago to stay under [maxBytes].
     *
     * This is where the SDK writes what it streams.
     */
    data class LeastRecentlyUsed(val maxBytes: Long) : LightCacheEviction {
        init {
            require(maxBytes > 0) { "Cache size must be positive, but was $maxBytes" }
        }
    }

    /**
     * Discards nothing. The SDK does not write here during streaming; nothing
     * in the current API fills this cache yet.
     */
    data object Never : LightCacheEviction
}

/** Distinct names and at most one size-limited cache. */
internal fun validateMediaCaches(caches: List<LightMediaCache>) {
    val names = caches.map(LightMediaCache::name)
    require(names.size == names.toSet().size) {
        "Each cache must have a distinct name, but got $names"
    }
    require(
        caches.count { it.eviction is LightCacheEviction.LeastRecentlyUsed } <= 1
    ) {
        "At most one cache may be size-limited, since that is the one written while streaming"
    }
}
