package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

// Playlist covers through Navidrome's own API, signed in with the password.
class NavidromeNativeTest {
    private val server = MockWebServer()
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private var tokens = 0
    private var expireFirst = false
    private var image: (RecordedRequest) -> MockResponse = { MockResponse.Builder().body("""{"status":"ok"}""").build() }

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/auth/login") -> {
                        tokens++
                        MockResponse.Builder().body("""{"id":"u1","name":"Alex","username":"alex","isAdmin":false,"token":"tok$tokens"}""").build()
                    }
                    expireFirst && request.headers["X-ND-Authorization"] == "Bearer tok1" -> MockResponse.Builder().code(401).body("").build()
                    path.endsWith("/image") -> image(request)
                    path.endsWith("/api/playlist") ->
                        MockResponse.Builder().body("""[{"id":"p1","name":"Mine","uploadedImage":"p1_abc.jpg"},{"id":"p2","name":"Plain","uploadedImage":""},{"id":"or1","name":"Station"}]""").build()
                    else -> MockResponse.Builder().code(404).body("404 page not found\n").build()
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun client(mode: AuthMode = AuthMode.Token, path: String = "/") =
        SubsonicClient(server.url(path), Credentials("alex", "secret", mode), OkHttpClient())

    @Test
    fun aCoverIsUploadedAsTheImageFieldWithTheToken() = runTest {
        client().navidrome.setPlaylistCover("p1", byteArrayOf(1, 2, 3), "image/png", "cover.png")
        val login = calls.first { it.url.encodedPath == "/auth/login" }
        assertEquals("POST", login.method)
        assertTrue(login.body!!.utf8().contains("\"password\":\"secret\""))
        val upload = calls.single { it.url.encodedPath == "/api/playlist/p1/image" }
        assertEquals("POST", upload.method)
        assertEquals("Bearer tok1", upload.headers["X-ND-Authorization"])
        val form = upload.body!!.utf8()
        assertTrue(form, form.contains("name=\"image\"; filename=\"cover.png\""))
        assertTrue(form, form.contains("Content-Type: image/png"))
    }

    @Test
    fun removingACoverDeletesItsImage() = runTest {
        client().navidrome.removePlaylistCover("p1")
        assertEquals("DELETE", calls.single { it.url.encodedPath == "/api/playlist/p1/image" }.method)
    }

    @Test
    fun oneSignInServesEveryCallAndAnExpiredTokenSignsInAgain() = runTest {
        val native = client().navidrome
        native.playlistsWithCovers()
        native.removePlaylistCover("p1")
        assertEquals(1, tokens)
        expireFirst = true
        native.removePlaylistCover("p1")
        assertEquals(2, tokens)
        assertEquals("Bearer tok2", calls.last().headers["X-ND-Authorization"])
    }

    @Test
    fun onlyPlaylistsWithAnUploadedImageCount() = runTest {
        assertEquals(setOf("p1"), client().navidrome.playlistsWithCovers())
    }

    @Test
    fun aServerUnderAPathIsCalledUnderIt() = runTest {
        client(path = "/octo/").navidrome.removePlaylistCover("p1")
        assertTrue(calls.any { it.url.encodedPath == "/octo/auth/login" })
        assertTrue(calls.any { it.url.encodedPath == "/octo/api/playlist/p1/image" })
    }

    @Test
    fun anApiKeySignInCannotChangeCovers() = runTest {
        val native = client(AuthMode.ApiKey).navidrome
        assertFalse(native.usable)
        try {
            native.removePlaylistCover("p1")
            fail("an API key has no password")
        } catch (e: SubsonicException.Server) {
            assertTrue(e.message!!.contains("password"))
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun navidromesRefusalsSayWhy() = runTest {
        suspend fun said(status: Int, body: String): String {
            image = { MockResponse.Builder().code(status).body(body).build() }
            return try {
                client().navidrome.setPlaylistCover("p1", byteArrayOf(1), "image/jpeg")
                "taken"
            } catch (e: SubsonicException) {
                e.message.orEmpty()
            }
        }
        assertTrue(said(403, "artwork upload is disabled\n").contains("turned off"))
        assertTrue(said(403, "not authorized\n").contains("owner"))
        assertTrue(said(404, "404 page not found\n").contains("0.61"))
        assertTrue(said(404, "not found\n").contains("not on the server"))
        assertTrue(said(400, "file too large or invalid form\n").contains("too large"))
        assertTrue(said(405, """{"error":"Octo's generated playlists are read-only"}""").contains("Octo's own"))
    }
}
