package app.winters.octo.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// Navidrome's own API, for what Subsonic has no call for: a playlist's
// uploaded cover (Navidrome 0.61 on). It is reached at the server's own
// address, as the Subsonic calls are; Octo passes it on to Navidrome, and
// signs a family member in there with their own account. It signs in with
// the account's password (POST /auth/login) and sends the token it gets
// back as X-ND-Authorization; a sign-in made with an API key has no
// password to give, so it cannot be used.
class NavidromeNative internal constructor(
    private val base: () -> HttpUrl,
    private val http: OkHttpClient,
    private val username: () -> String,
    private val password: () -> String?,
) {
    @Volatile private var token: String? = null
    private val signing = Mutex()

    // Set once the server said it has no such API (not Navidrome, or an old
    // one), so it is not asked again while this client lives.
    @Volatile private var absent = false

    // Whether this sign-in can reach Navidrome's own API at all.
    val usable: Boolean get() = password() != null && !absent

    // The ids of the playlists that have a cover of their own uploaded, or
    // null when the answer is not Navidrome's list (nothing is known then).
    suspend fun playlistsWithCovers(): Set<String>? {
        val text = call("playlists") { url, auth ->
            Request.Builder().url(url.newBuilder().addPathSegments("api/playlist").addQueryParameter("_start", "0").addQueryParameter("_end", "5000").build()).header(HEADER, auth).build()
        }
        val list = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        return list.mapNotNullTo(HashSet()) { row ->
            val playlist = row as? JsonObject ?: return@mapNotNullTo null
            val image = (playlist["uploadedImage"] as? JsonPrimitive)?.contentOrNull
            playlist["id"]?.jsonPrimitive?.contentOrNull?.takeIf { !image.isNullOrBlank() }
        }
    }

    // Uploads `image` (`type` such as "image/jpeg") as the playlist's cover.
    suspend fun setPlaylistCover(id: String, image: ByteArray, type: String, fileName: String = "cover") {
        call("cover") { url, auth ->
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("image", fileName, image.toRequestBody(type.toMediaType()))
                .build()
            Request.Builder().url(imageUrl(url, id)).header(HEADER, auth).post(form).build()
        }
    }

    // Takes the playlist's uploaded cover away, back to the one Navidrome makes.
    suspend fun removePlaylistCover(id: String) {
        call("cover") { url, auth -> Request.Builder().url(imageUrl(url, id)).header(HEADER, auth).delete().build() }
    }

    private fun imageUrl(base: HttpUrl, id: String): HttpUrl =
        base.newBuilder().addPathSegment("api").addPathSegment("playlist").addPathSegment(id).addPathSegment("image").build()

    // One call with the token, signing in first when there is none, and
    // once more when Navidrome says the token is no good any more.
    private suspend fun call(what: String, build: (HttpUrl, String) -> Request): String {
        val first = token ?: signIn(null)
        val (status, body) = send(build(base(), "Bearer $first"))
        if (status != 401) return answer(what, status, body)
        val again = signIn(first)
        val (status2, body2) = send(build(base(), "Bearer $again"))
        return answer(what, status2, body2)
    }

    private fun answer(what: String, status: Int, body: String): String {
        if (status in 200..299) return body
        throw navidromeFailure(what, status, body)
    }

    // A token, signing in unless another call already did since `stale`.
    private suspend fun signIn(stale: String?): String = signing.withLock {
        token?.takeIf { it != stale }?.let { return it }
        val secret = password() ?: throw SubsonicException.Server(0, NO_PASSWORD)
        val sent = JsonObject(mapOf("username" to JsonPrimitive(username()), "password" to JsonPrimitive(secret)))
        val request = Request.Builder()
            .url(base().newBuilder().addPathSegments("auth/login").build())
            .post(json.encodeToString(JsonObject.serializer(), sent).toRequestBody(JSON))
            .build()
        val (status, body) = send(request)
        if (status == 401 || status == 403) throw SubsonicException.WrongCredentials("The server did not take this account's password for that.")
        if (status == 404) {
            absent = true
            throw SubsonicException.Server(404, NOT_NAVIDROME)
        }
        if (status !in 200..299) throw SubsonicException.Server(status, "The server could not sign in to Navidrome (HTTP $status).")
        val got = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: throw SubsonicException.Server(0, NOT_NAVIDROME)
        token = got
        got
    }

    private suspend fun send(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).await().use { it.code to it.body.string() }
        } catch (e: IOException) {
            throw SubsonicException.Unreachable(e)
        }
    }

    private companion object {
        const val HEADER = "X-ND-Authorization"
        val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
        const val NO_PASSWORD = "This app signed in with an API key, and changing a cover needs the account's password. Sign in again with your password."
        const val NOT_NAVIDROME = "This server is not Navidrome, so playlist covers can't be changed here."
    }
}

// What Navidrome's answer to a cover call means, in words for the listener.
// Its errors are plain text ("not authorized", "artwork upload is disabled");
// an address it has no route for answers "404 page not found", which is a
// Navidrome older than 0.61. Octo refuses its own playlists' covers.
internal fun navidromeFailure(what: String, status: Int, body: String): SubsonicException {
    val said = body.trim()
    val lower = said.lowercase()
    return when {
        status == 403 && "upload is disabled" in lower ->
            SubsonicException.Server(403, "Uploading covers is turned off on this Navidrome (EnableArtworkUpload).")
        status == 403 -> SubsonicException.Server(403, "Only the playlist's owner can change its cover.")
        status == 405 -> SubsonicException.Server(405, "Octo's own playlists keep the covers Octo gives them.")
        status == 404 && "page not found" in lower ->
            SubsonicException.Server(404, if (what == "cover") "This server's Navidrome can't take playlist covers. It needs Navidrome 0.61 or newer." else "This server's Navidrome has no playlist covers.")
        status == 404 -> SubsonicException.NotFound("That playlist is not on the server any more.")
        status == 400 && ("too large" in lower || "invalid form" in lower) ->
            SubsonicException.Server(400, "That image is too large for the server.")
        status == 400 -> SubsonicException.Server(400, "The server can't use that image${if (said.isNotEmpty()) ": $said" else "."}")
        else -> SubsonicException.Server(status, "The server said no (HTTP $status).")
    }
}
