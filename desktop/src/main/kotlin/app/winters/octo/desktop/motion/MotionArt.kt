package app.winters.octo.desktop.motion

import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.settings.MotionCoverPrefs
import app.winters.octo.subsonic.OCTO_ANIMATED_ARTWORK
import app.winters.octo.subsonic.SubsonicClient
import androidx.compose.runtime.mutableStateMapOf
import app.winters.octo.subsonic.animatedArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// Finds an album's animated cover and keeps it on this computer. The
// signed-in Octo server is asked for the album (octoAnimatedArtwork, by its
// id, or its artist and name); it answers with Apple Music's HLS playlist
// for the loop, or that there is none. The H.264 copy nearest the size it
// is shown at is saved whole, a few megabytes, and played from the file
// from then on: no stream stays open while it loops.
//
// The loop itself comes from Apple's servers. Nothing is asked while the
// setting is off or the server does not offer animated covers.
class MotionArt(
    private val http: OkHttpClient,
    private val prefs: () -> MotionCoverPrefs,
    private val connection: () -> Connection?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Where loops and lookups are kept; none (the tests and screenshots)
    // keeps nothing and finds nothing.
    @Volatile
    var folder: File? = null

    // One lookup or download at a time for each album, and a few at once in
    // all: a grid of albums asking together stays well inside what the
    // artwork server behind Octo allows (20 requests a second).
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val asking = Semaphore(MAX_ASKING)

    // Lookups by album, read from the folder once.
    private var lookups: MutableMap<String, Lookup>? = null
    private val lookupsLock = Any()

    // Albums whose lookup failed for a reason that may pass (the server
    // down or busy), and when it may be asked again. Kept only while the
    // app runs.
    private val resting = ConcurrentHashMap<String, Long>()

    // Whether the signed-in server offers animated covers: as it said at
    // sign-in, or when asked since (`learn`), so a server that took them up
    // after the listener signed in counts without signing in again.
    fun offered(): Boolean {
        val connection = connection() ?: return false
        return connection.supports(OCTO_ANIMATED_ARTWORK) || learned[connection.client] == true
    }

    // What each signed-in client's server said of animated covers when
    // asked; read by the window, so a change shows.
    private val learned = mutableStateMapOf<SubsonicClient, Boolean>()

    // Asks the server in use whether it offers animated covers now, once
    // for each sign-in. Quietly nothing when it cannot be reached.
    suspend fun learn(connection: Connection?) {
        if (connection == null || connection.supports(OCTO_ANIMATED_ARTWORK) || connection.client in learned) return
        val offers = runCatching { connection.client.extensions() }.getOrNull()
            ?.any { it.name == OCTO_ANIMATED_ARTWORK && 1 in it.versions } ?: return
        learned[connection.client] = offers
    }

    // The loop for an album shown `px` pixels across, as a file ready to
    // play, or null when it has none, the setting is off, the server does
    // not offer them, or it cannot be had just now. `albumId` is asked by
    // when known (a library album or one found online); else its names.
    suspend fun loopFor(albumId: String?, artist: String, album: String, px: Int): File? {
        if (!prefs().enabled) return null
        if (!offered()) return null
        val connection = connection() ?: return null
        val folder = folder ?: return null
        if (albumId.isNullOrBlank() && (artist.isBlank() || album.isBlank())) return null
        // The account, not a server id an older Octo may have left empty.
        val key = lookupKey("${connection.server.username}@${connection.server.address}", albumId, artist, album)
        if ((resting[key] ?: 0) > now()) return null
        return locks.getOrPut(key) { Mutex() }.withLock {
            asking.withPermit {
                withContext(Dispatchers.IO) {
                    try {
                        load(folder, connection, key, albumId, artist, album, px)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Unreachable, busy, or an answer that makes no sense:
                        // the still cover stays, and the album rests a while.
                        resting[key] = now() + RETRY_AFTER_MS
                        null
                    }
                }
            }
        }
    }

    private suspend fun load(folder: File, connection: Connection, key: String, albumId: String?, artist: String, album: String, px: Int): File? {
        folder.mkdirs()
        val found = lookup(folder, key) ?: search(connection, albumId, artist, album).also { remember(folder, key, it) }
        val master = found.master?.toHttpUrlOrNull() ?: return null
        val variants = hlsVariants(masterPlaylist(folder, master), master)
        val variant = pickVariant(variants, px) ?: return null
        val file = File(folder, "${hash(variant.url.toString())}.mp4")
        if (file.isFile && file.length() > 0) {
            file.setLastModified(now())
            return file
        }
        save(variant, file)
        prune(folder, keep = file)
        return file
    }

    // What the server says about the album: its playlist, or none. Throws
    // when it cannot say just now.
    private suspend fun search(connection: Connection, albumId: String?, artist: String, album: String): Lookup {
        val found = connection.client.animatedArtwork(albumId, artist, album)
        // The square loop is the one that fits a cover; an album with only
        // the tall one has none to show here.
        return Lookup(found?.url?.takeIf(String::isNotBlank), now())
    }

    // The master playlist, kept beside the loops so a loop already saved
    // plays without asking anyone.
    private fun masterPlaylist(folder: File, master: HttpUrl): String {
        val kept = File(folder, "${hash(master.toString())}.m3u8")
        if (kept.isFile) return kept.readText()
        val text = fetchText(master)
        writeAtomically(kept) { it.writeText(text) }
        return text
    }

    // Saves a copy's pieces, joined, as one file.
    private fun save(variant: HlsVariant, file: File) {
        val pieces = joinedPieces(hlsPieces(fetchText(variant.url), variant.url))
        if (pieces.isEmpty()) throw IOException("no pieces in ${variant.url}")
        writeAtomically(file) { out ->
            out.outputStream().use { stream ->
                var total = 0L
                for (piece in pieces) {
                    val request = Request.Builder().url(piece.url)
                    if (piece.offset != null) {
                        val last = piece.end?.minus(1)?.toString().orEmpty()
                        request.header("Range", "bytes=${piece.offset}-$last")
                    }
                    http.newCall(request.build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("${piece.url} answered ${response.code}")
                        // A server that sends the whole file for a range
                        // would join the wrong bytes.
                        if (piece.offset != null && piece.offset > 0 && response.code != 206) throw IOException("${piece.url} ignored the range")
                        response.body.byteStream().use { body ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = body.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > MAX_LOOP_BYTES) throw IOException("loop larger than $MAX_LOOP_BYTES bytes")
                                stream.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fetchText(url: HttpUrl): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$url answered ${response.code}")
            response.body.string()
        }

    // A lookup still fresh enough to trust: one that found a loop for a
    // month, one that found none for a week, since albums gain them.
    private fun lookup(folder: File, key: String): Lookup? {
        val found = synchronized(lookupsLock) { lookups(folder)[key] } ?: return null
        val age = now() - found.atMs
        val fresh = if (found.master != null) FOUND_FOR_MS else NONE_FOR_MS
        return found.takeIf { age in 0 until fresh }
    }

    private fun remember(folder: File, key: String, lookup: Lookup) {
        val snapshot = synchronized(lookupsLock) {
            val all = lookups(folder)
            all[key] = lookup
            // The oldest go first once there are many.
            if (all.size > MAX_LOOKUPS) {
                all.entries.sortedBy { it.value.atMs }.take(all.size - MAX_LOOKUPS).forEach { all.remove(it.key) }
            }
            all.toMap()
        }
        runCatching { writeAtomically(File(folder, LOOKUPS_FILE)) { it.writeText(json.encodeToString(snapshot)) } }
    }

    private fun lookups(folder: File): MutableMap<String, Lookup> =
        lookups ?: runCatching {
            json.decodeFromString<Map<String, Lookup>>(File(folder, LOOKUPS_FILE).readText()).toMutableMap()
        }.getOrElse { mutableMapOf() }.also { lookups = it }

    // Keeps the saved loops under their limit, the ones shown longest ago
    // going first; `keep` (just saved) stays.
    private fun prune(folder: File, keep: File) {
        val loops = folder.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.toList().orEmpty()
        var total = loops.sumOf { it.length() }
        for (loop in loops.sortedBy { it.lastModified() }) {
            if (total <= MAX_FOLDER_BYTES) break
            if (loop == keep) continue
            total -= loop.length()
            loop.delete()
        }
    }

    companion object {
        const val LOOKUPS_FILE = "lookups.json"
        const val FOUND_FOR_MS = 30L * 24 * 60 * 60 * 1000
        const val NONE_FOR_MS = 7L * 24 * 60 * 60 * 1000
        const val RETRY_AFTER_MS = 10L * 60 * 1000
        const val MAX_LOOKUPS = 5000
        const val MAX_ASKING = 4
        const val MAX_LOOP_BYTES = 40L * 1024 * 1024
        const val MAX_FOLDER_BYTES = 400L * 1024 * 1024
    }
}

// One album's lookup: the master playlist's address, or null for none,
// and when it was asked.
@Serializable
internal data class Lookup(val master: String? = null, val atMs: Long = 0)

private val json = Json { ignoreUnknownKeys = true }

// An album's lookup is kept under its server and its id, or its names in
// any case when it has none.
internal fun lookupKey(server: String, albumId: String?, artist: String, album: String): String =
    if (!albumId.isNullOrBlank()) "$server|id|$albumId" else "$server|${artist.trim().lowercase()}|${album.trim().lowercase()}"

private fun hash(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

// Writes through a file beside it, so a half-written one is never read.
private fun writeAtomically(file: File, write: (File) -> Unit) {
    val part = File(file.parentFile, "${file.name}.part")
    try {
        write(part)
        if (!part.renameTo(file)) {
            file.delete()
            if (!part.renameTo(file)) throw IOException("could not keep $file")
        }
    } finally {
        part.delete()
    }
}
