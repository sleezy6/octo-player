package app.winters.octo.desktop

import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.PlaylistWithSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A playlist's own cover, uploaded to Navidrome through the server.
class PlaylistCoversTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun app(extensions: List<String> = emptyList()): AppState {
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        return AppState(settings, Accounts(settings, SessionOnlySecrets(), http), http, scope, DesktopOs.Windows, SilentPlayer(), OnlineLyrics(http, server.address.toHttpUrl()), restored = server.connection(extensions))
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(what())
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()

    private fun picture(w: Int, h: Int, format: EncodedImageFormat, name: String): File {
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.clear(0xFF3366CC.toInt())
        val bytes = surface.makeImageSnapshot().encodeToData(format, 90)!!.bytes
        return File(temp.root, name).apply { writeBytes(bytes) }
    }

    private val mine = Playlist("p1", "Road trip", owner = "winters")

    @Test
    fun aSmallPictureGoesAsItIs() {
        val file = picture(600, 600, EncodedImageFormat.PNG, "cover.png")
        val sent = coverUpload(file)!!
        assertEquals("image/png", sent.type)
        assertTrue(sent.bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun aHugePictureGoesAsAJpegAtMostTwoThousandAcross() {
        val sent = coverUpload(picture(4000, 3000, EncodedImageFormat.PNG, "huge.png"))!!
        assertEquals("image/jpeg", sent.type)
        assertEquals("huge.jpg", sent.name)
        val image = Image.makeFromEncoded(sent.bytes)
        assertEquals(2000, image.width)
        assertEquals(1500, image.height)
    }

    @Test
    fun aFileThatIsNoPictureIsRefused() {
        val file = File(temp.root, "notes.png").apply { writeText("not a picture") }
        assertEquals(null, runCatching { coverUpload(file) }.getOrNull())
    }

    @Test
    fun theCoverGoesToNavidromeAndShowsAsTheListenersOwn() {
        server.respondBy("login") { json("""{"token":"t1","username":"winters"}""") }
        server.respondBy("image") { json("""{"status":"ok"}""") }
        server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"p1","name":"Road trip","owner":"winters"}]}""")
        server.respondBy("playlist") { json("""[{"id":"p1","name":"Road trip","uploadedImage":"p1_a.jpg"}]""") }
        val app = app()
        app.setCover(mine, picture(300, 300, EncodedImageFormat.JPEG, "c.jpg"))
        waitFor { app.notice != null }
        assertEquals("Changed the cover of Road trip", app.notice)
        val upload = server.calls.single { it.url.encodedPath == "/api/playlist/p1/image" }
        assertEquals("POST", upload.method)
        assertEquals("Bearer t1", upload.headers["X-ND-Authorization"])
        // The playlists are read again after: the cover stays the listener's own.
        waitFor { server.calls.any { it.url.encodedPath == "/api/playlist" } }
        Thread.sleep(100)
        assertTrue(app.hasOwnCover(mine))
    }

    @Test
    fun aListThatCannotBeReadKeepsWhatWasKnown() {
        server.respondBy("login") { json("""{"token":"t1"}""") }
        server.respondBy("image") { json("""{"status":"ok"}""") }
        server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"p1","name":"Road trip","owner":"winters"}]}""")
        server.respondBy("playlist") { json("<html>busy</html>", 502) }
        val app = app()
        app.setCover(mine, picture(300, 300, EncodedImageFormat.JPEG, "c.jpg"))
        waitFor { server.calls.any { it.url.encodedPath == "/api/playlist" } }
        Thread.sleep(100)
        assertTrue(app.hasOwnCover(mine))
    }

    @Test
    fun removingTheCoverGoesBackToTheMadeOne() {
        server.respondBy("login") { json("""{"token":"t1"}""") }
        server.respondBy("image") { json("""{"status":"ok"}""") }
        val app = app()
        app.ownCovers = setOf("p1")
        app.removeCover(mine)
        waitFor { app.notice != null }
        assertEquals("DELETE", server.calls.single { it.url.encodedPath == "/api/playlist/p1/image" }.method)
        assertFalse(app.hasOwnCover(mine))
    }

    @Test
    fun aRefusalSaysWhy() {
        server.respondBy("login") { json("""{"token":"t1"}""") }
        server.respondBy("image") { json("artwork upload is disabled\n", 403) }
        val app = app()
        app.setCover(mine, picture(100, 100, EncodedImageFormat.PNG, "c.png"))
        waitFor { app.notice != null }
        assertTrue(app.notice!!.contains("turned off"), app.notice)
        assertFalse(app.hasOwnCover(mine))
    }

    private val ours = Playlist("of1", "Ours", owner = "Family", coverArt = "of1_old")

    @Test
    fun aFamilyCoverIsThePicturesMiddleSquareAsAJpeg() {
        val sent = familyCoverJpeg(picture(3000, 2000, EncodedImageFormat.PNG, "wide.png").readBytes())
        val image = Image.makeFromEncoded(sent)
        assertEquals(1500, image.width)
        assertEquals(1500, image.height)
        val small = Image.makeFromEncoded(familyCoverJpeg(picture(400, 900, EncodedImageFormat.PNG, "tall.png").readBytes()))
        assertEquals(400, small.width)
        assertEquals(400, small.height)
    }

    @Test
    fun aFamilyCoverGoesToOctoAndItsNewCoverShowsAtOnce() {
        server.respondBy("setFamilyPlaylistCover") { json(server.ok(""""familyPlaylistCover":{"id":"of1","coverArt":"of1_new"}""", type = "octo")) }
        server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"of1","name":"Ours","owner":"Family","coverArt":"of1_new"}]}""")
        val app = app(listOf("octoFamily:1", "octoFamily:2"))
        app.setCover(ours, picture(800, 600, EncodedImageFormat.PNG, "c.png"))
        waitFor { app.notice != null }
        assertEquals("Changed the cover of Ours", app.notice)
        val sent = server.calls.single { it.url.pathSegments.last() == "setFamilyPlaylistCover" }
        assertEquals("POST", sent.method)
        assertEquals("of1", sent.url.queryParameter("id"))
        assertEquals("image/jpeg", sent.headers["Content-Type"])
        assertTrue(server.calls.none { it.url.encodedPath.endsWith("/image") }, "never Navidrome's own API")
    }

    @Test
    fun thePagesOwnCopyTakesTheNewCoverToo() {
        server.respondBy("setFamilyPlaylistCover") { json(server.ok(""""familyPlaylistCover":{"id":"of1","coverArt":"of1_new"}""", type = "octo")) }
        server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"of1","name":"Ours","owner":"Family","coverArt":"of1_new"}]}""")
        val app = app(listOf("octoFamily:2"))
        val client = app.connection!!.client
        app.playlistViews["of1"] = PlaylistView(client, PlaylistWithSongs("of1", "Ours", owner = "Family", coverArt = "of1_old"))
        app.setCover(ours, picture(300, 300, EncodedImageFormat.PNG, "c.png"))
        waitFor { app.notice != null }
        assertEquals("of1_new", app.playlistView("of1")?.coverArt)
    }

    @Test
    fun aFamilyCoverRefusedSaysWhy() {
        server.answerBy("setFamilyPlaylistCover") { """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":50,"message":"Only the person who made this playlist or a family manager can change its cover."}}}""" }
        val app = app(listOf("octoFamily:2"))
        app.setCover(ours, picture(300, 300, EncodedImageFormat.PNG, "c.png"))
        waitFor { app.notice != null }
        assertTrue(app.notice!!.contains("family manager"), app.notice)
    }

    @Test
    fun familyPlaylistsShowOctosCoverOnlyOnAServerThatGivesThem() {
        assertTrue(app(listOf("octoFamily:2")).hasOwnCover(ours))
        assertFalse(app(listOf("octoFamily:1")).hasOwnCover(ours))
        assertFalse(app(listOf("octoFamily:2")).hasOwnCover(ours.copy(coverArt = null)))
        assertTrue(app(listOf("octoFamily:2")).familyCoverMayChange(ours))
        assertFalse(app(listOf("octoFamily:2")).familyCoverMayChange(ours.copy(readonly = true)))
    }

    @Test
    fun aServerUpdatedSinceTheSignInIsFollowedWithoutSigningInAgain() {
        server.answer("ping")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoFamily","versions":[1,2]}]""")
        // Signed in while the server listed only version 1.
        val app = app(listOf("octoFamily:1"))
        waitFor { app.familyCovers() }
        assertTrue(app.familyCoverMayChange(ours))
        assertTrue(app.hasOwnCover(ours))
    }

    @Test
    fun onlyTheListenersOwnPlaylistsChangeCoverHere() {
        val app = app()
        assertTrue(app.changesCover(mine))
        assertFalse(app.changesCover(Playlist("of1", "Ours", owner = "Family")))
        assertFalse(app.changesCover(Playlist("p9", "Sam's", owner = "sam")))
    }
}
