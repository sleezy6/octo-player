package app.winters.octo.desktop.motion

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.settings.MotionCoverPrefs
import app.winters.octo.subsonic.OCTO_ANIMATED_ARTWORK
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Finding and keeping animated covers, against a pretend Octo server that
// offers them (octoAnimatedArtwork) and also plays Apple's part: the
// playlists and the loop's file, served in byte ranges as Apple serves
// them. Never the real ones.
class MotionArtTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer()
    private var prefs = MotionCoverPrefs(enabled = true)
    private var clock = 1_000_000L

    // The loop's file: a setup part then two segments, as one file.
    private val loop = Random(7).nextBytes(10_000)

    // What getAnimatedArtwork answers: the loop, none, or a failure.
    private enum class Answer { Loop, None, NotFound, Unavailable }
    private var answer = Answer.Loop

    init {
        server.answerBy("getAnimatedArtwork") {
            when (answer) {
                Answer.Loop -> server.ok(""""animatedArtwork":{"url":"${server.address}assets/P1_default.m3u8","urlTall":"${server.address}assets/P1_tall.m3u8"}""")
                Answer.None -> server.ok()
                Answer.NotFound -> failed(70, "Album not found")
                Answer.Unavailable -> failed(0, "The animated artwork server did not answer; try again later")
            }
        }
        server.respondBy("P1_default.m3u8") { text(MASTER) }
        server.respondBy("P1_486.m3u8") { text(media("P1_486-.mp4")) }
        server.respondBy("P1_1080.m3u8") { text(media("P1_1080-.mp4")) }
        server.respondBy("P1_486-.mp4", ::ranged)
        server.respondBy("P1_1080-.mp4", ::ranged)
    }

    @After
    fun close() = server.close()

    private fun failed(code: Int, message: String) =
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}"""

    private fun text(body: String) = MockResponse.Builder().body(body).build()

    // The loop file, honouring a Range header as a CDN does.
    private fun ranged(request: RecordedRequest): MockResponse {
        val range = request.headers["Range"] ?: return MockResponse.Builder().body(okio.Buffer().write(loop)).build()
        val (from, to) = range.removePrefix("bytes=").split('-').map(String::toInt)
        return MockResponse.Builder().code(206).body(okio.Buffer().write(loop.copyOfRange(from, to + 1))).build()
    }

    private fun media(file: String) = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-MAP:URI="$file",BYTERANGE="1000@0"
        #EXTINF:2.8,
        #EXT-X-BYTERANGE:4000@1000
        $file
        #EXTINF:2.8,
        #EXT-X-BYTERANGE:5000@5000
        $file
        #EXT-X-ENDLIST
    """.trimIndent()

    private val offering = server.connection(listOf("$OCTO_ANIMATED_ARTWORK:1"))
    private var connection: Connection? = offering

    private fun art(withFolder: Boolean = true) = MotionArt(OkHttpClient(), { prefs }, { connection }, { clock }).also {
        if (withFolder) it.folder = folder.root
    }

    private fun asks() = server.calls.filter { it.url.pathSegments.last() == "getAnimatedArtwork" }
    private fun downloads() = server.calls.filter { it.url.pathSegments.last().endsWith("-.mp4") }

    @Test
    fun savesTheLoopWholeFromItsRanges(): Unit = runBlocking {
        val file = assertNotNull(art().loopFor("al-1", "Linkin Park", "Living Things", 400))
        assertContentEquals(loop, file.readBytes())
        // The three ranges of one file come as one request.
        assertEquals(1, downloads().size)
        assertEquals("bytes=0-9999", downloads().last().headers["Range"])
    }

    @Test
    fun asksTheServerByTheAlbumsId(): Unit = runBlocking {
        art().loopFor("al-1", "Linkin Park", "Living Things", 400)
        val ask = asks().single().url
        assertEquals("al-1", ask.queryParameter("id"))
        assertNull(ask.queryParameter("artist"))
        assertNull(ask.queryParameter("album"))
    }

    @Test
    fun withoutAnIdItAsksByTheNames(): Unit = runBlocking {
        art().loopFor(null, "Linkin Park", "Living Things", 400)
        val ask = asks().single().url
        assertEquals("Linkin Park", ask.queryParameter("artist"))
        assertEquals("Living Things", ask.queryParameter("album"))
        assertNull(ask.queryParameter("id"))
    }

    @Test
    fun picksTheCopyForTheSizeShown(): Unit = runBlocking {
        art().loopFor("al-1", "Linkin Park", "Living Things", 400)
        art().loopFor("al-1", "Linkin Park", "Living Things", 900)
        assertEquals(listOf("P1_486-.mp4", "P1_1080-.mp4"), downloads().map { it.url.pathSegments.last() })
    }

    @Test
    fun aSavedLoopPlaysWithoutAskingAnyone(): Unit = runBlocking {
        art().loopFor("al-1", "Linkin Park", "Living Things", 400)
        val before = server.calls.size
        // A new app run: the lookup and the playlist come from the folder.
        val again = assertNotNull(art().loopFor("al-1", "Linkin Park", "Living Things", 400))
        assertContentEquals(loop, again.readBytes())
        assertEquals(before, server.calls.size)
    }

    @Test
    fun anAlbumWithoutOneIsNotAskedAboutAgainForAWeek(): Unit = runBlocking {
        answer = Answer.None
        val art = art()
        assertNull(art.loopFor("al-2", "Someone", "Plain Album", 400))
        assertNull(art.loopFor("al-2", "Someone", "Plain Album", 400))
        assertEquals(1, asks().size)
        clock += MotionArt.NONE_FOR_MS + 1
        answer = Answer.Loop
        assertNotNull(art.loopFor("al-2", "Someone", "Plain Album", 400))
        assertEquals(2, asks().size)
    }

    @Test
    fun anAlbumTheServerDoesNotKnowHasNone(): Unit = runBlocking {
        answer = Answer.NotFound
        val art = art()
        assertNull(art.loopFor("al-gone", "Someone", "Gone", 400))
        assertNull(art.loopFor("al-gone", "Someone", "Gone", 400))
        assertEquals(1, asks().size)
    }

    @Test
    fun aServerThatCannotSayIsLeftAloneAWhile(): Unit = runBlocking {
        answer = Answer.Unavailable
        val art = art()
        assertNull(art.loopFor("al-1", "Linkin Park", "Living Things", 400))
        assertNull(art.loopFor("al-1", "Linkin Park", "Living Things", 400))
        assertEquals(1, asks().size)
        // A failure is not remembered as "none": once rested, it asks again.
        answer = Answer.Loop
        clock += MotionArt.RETRY_AFTER_MS + 1
        assertNotNull(art.loopFor("al-1", "Linkin Park", "Living Things", 400))
    }

    @Test
    fun nothingIsAskedWhileOffOrNotOffered(): Unit = runBlocking {
        prefs = prefs.copy(enabled = false)
        assertNull(art().loopFor("al-1", "Linkin Park", "Living Things", 400))
        prefs = prefs.copy(enabled = true)
        connection = server.connection(listOf("octoLyrics:1"))
        assertNull(art().loopFor("al-1", "Linkin Park", "Living Things", 400))
        connection = null
        assertNull(art().loopFor("al-1", "Linkin Park", "Living Things", 400))
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun offeredFollowsTheSignedInServer() {
        val art = art()
        assertTrue(art.offered())
        connection = server.connection()
        assertTrue(!art.offered())
    }

    // Signed in before the server took animated covers up: asking it once
    // finds them, without signing in again.
    @Test
    fun aServerThatTookThemUpSinceSignInIsAsked(): Unit = runBlocking {
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoLyrics","versions":[1]},{"name":"$OCTO_ANIMATED_ARTWORK","versions":[1]}]""")
        connection = server.connection(listOf("octoLyrics:1"))
        val art = art()
        assertTrue(!art.offered())
        art.learn(connection)
        assertTrue(art.offered())
        assertNotNull(art.loopFor("al-1", "Linkin Park", "Living Things", 400))
        // Asked once for this sign-in.
        art.learn(connection)
        assertEquals(1, server.calls.count { it.url.pathSegments.last() == "getOpenSubsonicExtensions" })
    }

    @Test
    fun aServerThatStillDoesNotStaysOff(): Unit = runBlocking {
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoLyrics","versions":[1]}]""")
        connection = server.connection(listOf("octoLyrics:1"))
        val art = art()
        art.learn(connection)
        assertTrue(!art.offered())
        assertNull(art.loopFor("al-1", "Linkin Park", "Living Things", 400))
    }

    @Test
    fun nothingIsKeptWithoutAFolder(): Unit = runBlocking {
        assertNull(art(withFolder = false).loopFor("al-1", "Linkin Park", "Living Things", 400))
        assertTrue(server.calls.isEmpty())
    }

    // A settings file from before, with the artwork server's address, still reads.
    @Test
    fun anOldSettingsFileStillReads() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val read = json.decodeFromString(MotionCoverPrefs.serializer(), """{"enabled":true,"server":"https://artwork.example.com","grids":"Always"}""")
        assertEquals(MotionCoverPrefs(enabled = true, grids = app.winters.octo.desktop.settings.MotionGrids.Always), read)
    }

    companion object {
        // Two H.264 copies and an HEVC one, which is never picked.
        val MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=761716,BANDWIDTH=845140,VIDEO-RANGE=SDR,CODECS="avc1.64001f",RESOLUTION=486x486
            small/P1_486.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=1526948,BANDWIDTH=1791820,VIDEO-RANGE=SDR,CODECS="hvc1.2.20000000.L123.B0",RESOLUTION=768x768
            hevc/P1_768.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=3906377,BANDWIDTH=4242783,VIDEO-RANGE=SDR,CODECS="avc1.640020",RESOLUTION=1080x1080
            big/P1_1080.m3u8
        """.trimIndent()
    }
}
