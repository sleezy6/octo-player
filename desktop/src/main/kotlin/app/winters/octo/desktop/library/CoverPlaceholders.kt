package app.winters.octo.desktop.library

import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// Navidrome's stand-in for a missing cover: a vinyl record on a clear
// background, sent with a 200 like any cover. It is sent while it has not
// yet read an album's art (just after a scan, say) as well as for an album
// with none, and from 0.64 says so with Cache-Control: no-store. Kept as a
// cover, it stood in for the real art at that size for good, and drawn
// over another copy it showed the record on top of the album. So it is
// never kept, never shown: a cover that comes back as the stand-in is a
// cover that did not come, which leaves the tile and its glyph (or a copy
// at another size), and the playing song's cover is asked for again.

// Thrown for an answer that is the server's stand-in, not the cover.
class PlaceholderCover(what: String) : IOException("the server sent its stand-in for a missing cover: $what")

// The stand-in itself. Navidrome sends its file as it is, never resized, so
// every copy of one version is the same bytes; these are each version it
// has shipped, by length and SHA-256.
object NavidromePlaceholder {
    private val known: Map<Long, String> = mapOf(
        // album-placeholder.webp, since March 2025.
        69_228L to "273a4dbd61dfbb0a12d5d8ffe780eb7a3d4d000bc9771c5411cc70ae4dfa8a1f",
        // album-placeholder.webp, its first version (December 2024).
        17_464L to "b502979c464ab7a61f79bfd820e23b8dc3eeb36034b0722f7588972fd9fa7392",
        // placeholder.png, before.
        300_162L to "6ee8f3c379050e52f8c5cdf7eb215f6d14c38cc7c4d8c59000d619d7a6a07888",
    )

    // Whether a file this long could be the stand-in, so only those are read.
    fun mayBe(length: Long): Boolean = length in known

    fun matches(bytes: ByteArray): Boolean {
        val wanted = known[bytes.size.toLong()] ?: return false
        return sha256(bytes) == wanted
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

// Covers whose last download came back marked not to be kept, by their
// disk cache key, from the cache rule below to the fetch that asked.
object TransientCovers {
    private val marked = ConcurrentHashMap.newKeySet<String>()

    fun mark(key: String) {
        marked += key
    }

    fun clear(key: String) {
        marked -= key
    }

    // Whether the download for `key` was the stand-in, forgetting it.
    fun take(key: String): Boolean = marked.remove(key)
}

// Coil's own rule keeps every answer that is not an error. This one keeps
// nothing a server marked no-store (Navidrome's stand-in from 0.64), and
// marks the cover so the fetch that asked can say it did not come.
@OptIn(ExperimentalCoilApi::class)
object CoverCacheStrategy : CacheStrategy {
    override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options): CacheStrategy.ReadResult =
        CacheStrategy.DEFAULT.read(cacheResponse, networkRequest, options)

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult {
        val key = options.diskCacheKey
        if (noStore(networkResponse.headers.getAll("Cache-Control"))) {
            key?.let(TransientCovers::mark)
            return CacheStrategy.WriteResult.DISABLED
        }
        key?.let(TransientCovers::clear)
        return CacheStrategy.DEFAULT.write(cacheResponse, networkRequest, networkResponse, options)
    }
}

// Whether Cache-Control says no-store, in any of its lines.
internal fun noStore(cacheControl: List<String>): Boolean =
    cacheControl.flatMap { it.split(',') }.any { it.trim().substringBefore('=').equals("no-store", ignoreCase = true) }
