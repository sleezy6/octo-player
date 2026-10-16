package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AnimatedArtTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client(path: String = "/") = SubsonicClient(server.url(path), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun ok(inner: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonic":true$inner}}"""

    private fun error(code: Int, message: String) =
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}"""

    @Test
    fun bothClipsComeBackWithWholeAddresses() = runTest {
        answer(
            ok(
                ""","animatedArt":{
                  "square":{"video":"https://mvod.itunes.apple.com/a/square.m3u8","poster":"https://is1.mzstatic.com/a/1200x1200.jpg","color":"1a0904","width":3840,"height":3840},
                  "tall":{"video":"https://mvod.itunes.apple.com/a/tall.m3u8","width":2048,"height":2732}}""",
            ),
        )
        val answer = client().animatedArt("al-1") as AnimatedArtAnswer.Found
        assertEquals("https://mvod.itunes.apple.com/a/square.m3u8", answer.art.square?.video)
        assertEquals(0x1a0904, answer.art.square?.colorRgb)
        assertNull(answer.art.tall?.poster)
        assertNull(answer.art.tall?.colorRgb)
        assertEquals(2732, answer.art.tall?.height)

        val url = server.takeRequest().url
        assertEquals("/rest/getAnimatedArt", url.encodedPath)
        assertEquals("al-1", url.queryParameter("id"))
    }

    @Test
    fun relayedClipsArePutUnderTheServersOwnPath() = runTest {
        answer(ok(""","animatedArt":{"square":{"video":"animated/tok/square.m3u8","poster":"animated/tok/square.jpg","width":1,"height":1}}"""))
        val answer = client("/octo/").animatedArt("al-1") as AnimatedArtAnswer.Found
        assertEquals(server.url("/octo/animated/tok/square.m3u8").toString(), answer.art.square?.video)
        assertEquals(server.url("/octo/animated/tok/square.jpg").toString(), answer.art.square?.poster)
    }

    @Test
    fun noFieldMeansTheAlbumHasNone() = runTest {
        answer(ok(""))
        assertEquals(AnimatedArtAnswer.None, client().animatedArt("al-1"))
    }

    @Test
    fun anEmptyFieldMeansTheAlbumHasNone() = runTest {
        answer(ok(""","animatedArt":{}"""))
        assertEquals(AnimatedArtAnswer.None, client().animatedArt("al-1"))
    }

    @Test
    fun offIsToldApartFromAnOutage() = runTest {
        answer(error(0, "Animated art is off on this server"))
        assertEquals(AnimatedArtAnswer.Off, client().animatedArt("al-1"))
        answer(error(0, "Apple Music could not be asked just now; try again later"))
        assertEquals(AnimatedArtAnswer.NotNow, client().animatedArt("al-1"))
    }

    @Test
    fun anUnknownAlbumHasNone() = runTest {
        answer(error(70, "Album not found"))
        assertEquals(AnimatedArtAnswer.None, client().animatedArt("al-1"))
    }

    @Test
    fun anUnreachableServerIsAskedAgainLater() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("Bad gateway").build())
        assertEquals(AnimatedArtAnswer.NotNow, client().animatedArt("al-1"))
    }

    @Test
    fun getAlbumCarriesTheFieldResolved() = runTest {
        answer(
            ok(
                ""","album":{"id":"al-1","name":"Short n' Sweet","animatedArt":{"square":{"video":"animated/tok/square.m3u8","width":1,"height":1}},"song":[]}""",
            ),
        )
        val album = client().album("al-1")
        assertEquals(server.url("/animated/tok/square.m3u8").toString(), album.animatedArt?.square?.video)
    }

    @Test
    fun getAlbumWithoutTheFieldSaysNothing() = runTest {
        answer(ok(""","album":{"id":"al-1","name":"Short n' Sweet","song":[]}"""))
        assertNull(client().album("al-1").animatedArt)
    }

    @Test
    fun addressesResolveUnderAnyBase() {
        val bare = "https://music.example.com".toHttpUrl()
        val pathed = "https://music.example.com/octo".toHttpUrl()
        assertEquals("https://music.example.com/animated/t/tall.m3u8", resolveArtAddress("animated/t/tall.m3u8", bare).toString())
        assertEquals("https://music.example.com/octo/animated/t/tall.m3u8", resolveArtAddress("/animated/t/tall.m3u8", pathed).toString())
        assertEquals("https://cdn.example.com/x.m3u8", resolveArtAddress("https://cdn.example.com/x.m3u8", pathed).toString())
        assertNull(resolveArtAddress("ftp://cdn.example.com/x.m3u8", bare))
        assertNull(resolveArtAddress("  ", bare))
    }
}

// The fork's octoAnimatedArtwork (sleezy6/octo): the same covers asked
// through getAnimatedArtwork, read as AnimatedArt.
class AnimatedArtworkTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client(path: String = "/") =
        SubsonicClient(server.url(path), Credentials("winters", "secret"), OkHttpClient()).apply { animatedArtwork = true }

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun ok(inner: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonic":true$inner}}"""

    @Test
    fun relayedPlaylistsComeBackAsBothClips() = runTest {
        answer(ok(""","animatedArtwork":{"url":"animated-artwork/k1/square.m3u8","urlTall":"animated-artwork/k1/tall.m3u8"}"""))
        val answer = client("/octo/").animatedArt("al-1") as AnimatedArtAnswer.Found
        assertEquals(server.url("/octo/animated-artwork/k1/square.m3u8").toString(), answer.art.square?.video)
        assertEquals(server.url("/octo/animated-artwork/k1/tall.m3u8").toString(), answer.art.tall?.video)
        assertNull(answer.art.square?.poster)
        val url = server.takeRequest().url
        assertEquals("/octo/rest/getAnimatedArtwork", url.encodedPath)
        assertEquals("al-1", url.queryParameter("id"))
    }

    @Test
    fun aSquareOnlyAlbumHasNoTallClip() = runTest {
        answer(ok(""","animatedArtwork":{"url":"animated-artwork/k2/square.m3u8"}"""))
        val answer = client().animatedArt("al-1") as AnimatedArtAnswer.Found
        assertNull(answer.art.tall)
    }

    @Test
    fun noFieldOrBlankPlaylistsMeanNone() = runTest {
        answer(ok(""))
        assertEquals(AnimatedArtAnswer.None, client().animatedArt("al-1"))
        answer(ok(""","animatedArtwork":{"url":"","urlTall":null}"""))
        assertEquals(AnimatedArtAnswer.None, client().animatedArt("al-1"))
    }

    @Test
    fun getAlbumsFieldIsReadAsAnimatedArt() = runTest {
        answer(ok(""","album":{"id":"al-1","name":"Short n' Sweet","animatedArtwork":{"url":"animated-artwork/k3/square.m3u8"},"song":[]}"""))
        val album = client().album("al-1")
        assertEquals(server.url("/animated-artwork/k3/square.m3u8").toString(), album.animatedArt?.square?.video)
        assertNull(album.animatedArtwork)
    }

    @Test
    fun theForksWayIsTakenOnlyWithoutTheOriginal() {
        assertEquals(true, animatedArtworkOnly(art = false, artwork = true))
        assertEquals(false, animatedArtworkOnly(art = true, artwork = true))
        assertEquals(false, animatedArtworkOnly(art = false, artwork = false))
        val listed = listOf(Extension(OCTO_ANIMATED_ARTWORK, listOf(1)))
        assertEquals(true, listed.offersAnimatedArt())
        assertEquals(false, emptyList<Extension>().offersAnimatedArt())
    }
}
