package app.winters.octo.desktop.motion

import app.winters.octo.desktop.settings.MotionCoverPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
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
// listener's artwork server (an apple-music-animated-artworks instance)
// is asked for the album by its artist and name; it answers with Apple
// Music's HLS playlist for the loop, or that there is none. The H.264 copy
// nearest the size it is shown at is saved whole, a few megabytes, and
// played from the file from then on: no stream stays open while it loops.
//
// Only the album's artist and name go to the artwork server; the loop
// itself comes from Apple's servers. Nothing is asked while the setting is
// off or no server is set.
class MotionArt(
    private val http: OkHttpClient,
    private val prefs: () -> MotionCoverPrefs,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Where loops and lookups are kept; none (the tests and screenshots)
    // keeps nothing and finds nothing.
    @Volatile
    var folder: File? = null

    // One lookup or download at a time for each album.
    private val locks = ConcurrentHashMap<String, Mutex>()

    // Lookups by album, read from the folder once.
    private var lookups: MutableMap<String, Lookup>? = null
    private val lookupsLock = Any()

    // Albums whose lookup failed for a reason that may pass (the server
    // down or busy), and when it may be asked again. Kept only while the
    // app runs.
    private val resting = ConcurrentHashMap<String, Long>()

    // The loop for an album shown `px` pixels across, as a file ready to
    // play, or null when it has none, the setting is off, or it cannot be
    // had just now.
    suspend fun loopFor(artist: String, album: String, px: Int): File? {
        val settings = prefs()
        if (!settings.enabled) return null
        val server = artworkServer(settings.server) ?: return null
        val folder = folder ?: return null
        if (artist.isBlank() || album.isBlank()) return null
        val key = lookupKey(server, artist, album)
        if ((resting[key] ?: 0) > now()) return null
        return locks.getOrPut(key) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                try {
                    load(folder, server, key, artist, album, px)
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

    // Whether an artwork server answers at the address typed, for the
    // settings: its own words on how it is, or the reason it cannot be
    // reached. Asks only for its status, never about any album.
    suspend fun check(typed: String): ServerCheck = withContext(Dispatchers.IO) {
        val server = artworkServer(typed) ?: return@withContext ServerCheck(false, "That isn't a web address.")
        val url = server.newBuilder().addPathSegments("api/v1/status").build()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                when {
                    response.code == 503 -> ServerCheck(true, "Connected. It's still starting up.")
                    !response.isSuccessful -> ServerCheck(false, "It answered ${response.code}. Is this the artwork server's address?")
                    else -> {
                        val status = json.decodeFromString<ServerStatus>(response.body.string())
                        val found = status.totalAnimatedEntries?.let { " It knows $it animated covers." }.orEmpty()
                        if (status.status == "degraded") ServerCheck(true, "Connected, but Apple Music is limiting it for now.$found")
                        else ServerCheck(true, "Connected.$found")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerCheck(false, if (e is IOException) "Couldn't reach it: ${e.message}" else "That doesn't look like an artwork server.")
        }
    }

    private fun load(folder: File, server: HttpUrl, key: String, artist: String, album: String, px: Int): File? {
        folder.mkdirs()
        val found = lookup(folder, key) ?: search(server, artist, album).also { remember(folder, key, it) }
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

    // What the artwork server says about the album: its playlist, or none.
    // Throws IOException when it cannot say just now.
    private fun search(server: HttpUrl, artist: String, album: String): Lookup {
        val url = server.newBuilder()
            .addPathSegments("api/v1/artwork/search")
            .addQueryParameter("artist", artist)
            .addQueryParameter("album", album)
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 404) return Lookup(null, now())
            if (!response.isSuccessful) throw IOException("artwork server answered ${response.code}")
            val answer = json.decodeFromString<ArtworkAnswer>(response.body.string())
            // The square loop is the one that fits a cover; an album with
            // only the tall one has none to show here.
            return Lookup(answer.url?.takeIf(String::isNotBlank), now())
        }
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
        const val MAX_LOOP_BYTES = 40L * 1024 * 1024
        const val MAX_FOLDER_BYTES = 400L * 1024 * 1024
    }
}

// One album's lookup: the master playlist's address, or null for none,
// and when it was asked.
@Serializable
internal data class Lookup(val master: String? = null, val atMs: Long = 0)

// What the artwork server answers when it has a loop.
@Serializable
internal data class ArtworkAnswer(
    val url: String? = null,
    @SerialName("url_tall") val urlTall: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

// The artwork server's status, as far as the settings show it.
@Serializable
internal data class ServerStatus(val status: String? = null, val totalAnimatedEntries: Long? = null)

// What checking an artwork server found, in words for the settings.
data class ServerCheck(val ok: Boolean, val words: String)

private val json = Json { ignoreUnknownKeys = true }

// The artwork server's address as typed: "artwork.example.com" is taken
// as HTTPS; one on the home network may say http:// itself. Null when it
// is not an address.
fun artworkServer(typed: String): HttpUrl? {
    val text = typed.trim().trimEnd('/')
    if (text.isEmpty()) return null
    val withScheme = if ("://" in text) text else "https://$text"
    return withScheme.toHttpUrlOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }
}

// An album's lookup is kept under its server and its names, in any case.
internal fun lookupKey(server: HttpUrl, artist: String, album: String): String =
    "${server.host}:${server.port}${server.encodedPath}|${artist.trim().lowercase()}|${album.trim().lowercase()}"

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
