package app.winters.octo.desktop.motion

import app.winters.octo.desktop.settings.MotionCoverPrefs
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Finding and keeping animated covers, against a pretend artwork server
// that also plays Apple's part: the playlists and the loop's file, served
// in byte ranges as Apple serves them. Never the real ones.
class MotionArtTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private var prefs = MotionCoverPrefs(enabled = true)
    private var clock = 1_000_000L

    // The loop's file: a setup part then two segments, as one file.
    private val loop = Random(7).nextBytes(10_000)
    private var hasLoop = true
    private var searchCode = 200

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/api/v1/artwork/search") -> when {
                        searchCode != 200 -> MockResponse.Builder().code(searchCode).build()
                        !hasLoop -> MockResponse.Builder().code(404).body("""{"message":"No animated artwork found."}""").build()
                        else -> MockResponse.Builder().body(
                            """{"url":"${server.url("/assets/P1_default.m3u8")}","url_tall":null,"artist":"LINKIN PARK","album":"LIVING THINGS","isCached":true}""",
                        ).build()
                    }
                    path == "/assets/P1_default.m3u8" -> text(MASTER)
                    path == "/assets/small/P1_486.m3u8" -> text(media("P1_486-.mp4"))
                    path == "/assets/big/P1_1080.m3u8" -> text(media("P1_1080-.mp4"))
                    path.endsWith("-.mp4") -> ranged(request)
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        prefs = prefs.copy(server = server.url("/").toString())
    }

    @After
    fun close() = server.close()

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

    private fun art(withFolder: Boolean = true) = MotionArt(OkHttpClient(), { prefs }, { clock }).also {
        if (withFolder) it.folder = folder.root
    }

    private fun searches() = calls.count { it.url.encodedPath.endsWith("/search") }
    private fun downloads() = calls.count { it.url.encodedPath.endsWith("-.mp4") }

    @Test
    fun savesTheLoopWholeFromItsRanges(): Unit = runBlocking {
        val file = assertNotNull(art().loopFor("Linkin Park", "Living Things", 400))
        assertContentEquals(loop, file.readBytes())
        // The three ranges of one file come as one request.
        assertEquals(1, downloads())
        assertEquals("bytes=0-9999", calls.last { it.url.encodedPath.endsWith("-.mp4") }.headers["Range"])
    }

    @Test
    fun asksForTheAlbumByItsNamesOnly(): Unit = runBlocking {
        art().loopFor("Linkin Park", "Living Things", 400)
        val search = calls.first { it.url.encodedPath.endsWith("/search") }.url
        assertEquals("Linkin Park", search.queryParameter("artist"))
        assertEquals("Living Things", search.queryParameter("album"))
        assertEquals(setOf("artist", "album"), search.queryParameterNames)
    }

    @Test
    fun picksTheCopyForTheSizeShown(): Unit = runBlocking {
        art().loopFor("Linkin Park", "Living Things", 400)
        art().loopFor("Linkin Park", "Living Things", 900)
        val files = calls.filter { it.url.encodedPath.endsWith("-.mp4") }.map { it.url.pathSegments.last() }
        assertEquals(listOf("P1_486-.mp4", "P1_1080-.mp4"), files)
    }

    @Test
    fun aSavedLoopPlaysWithoutAskingAnyone(): Unit = runBlocking {
        art().loopFor("Linkin Park", "Living Things", 400)
        val before = calls.size
        // A new app run: the lookup and the playlist come from the folder.
        val again = assertNotNull(art().loopFor("linkin park ", "LIVING THINGS", 400))
        assertContentEquals(loop, again.readBytes())
        assertEquals(before, calls.size)
    }

    @Test
    fun anAlbumWithoutOneIsNotAskedAboutAgainForAWeek(): Unit = runBlocking {
        hasLoop = false
        val art = art()
        assertNull(art.loopFor("Someone", "Plain Album", 400))
        assertNull(art.loopFor("Someone", "Plain Album", 400))
        assertEquals(1, searches())
        clock += MotionArt.NONE_FOR_MS + 1
        hasLoop = true
        assertNotNull(art.loopFor("Someone", "Plain Album", 400))
        assertEquals(2, searches())
    }

    @Test
    fun aServerThatFailsIsLeftAloneAWhile(): Unit = runBlocking {
        searchCode = 503
        val art = art()
        assertNull(art.loopFor("Linkin Park", "Living Things", 400))
        assertNull(art.loopFor("Linkin Park", "Living Things", 400))
        assertEquals(1, searches())
        // A failure is not remembered as "none": once rested, it asks again.
        searchCode = 200
        clock += MotionArt.RETRY_AFTER_MS + 1
        assertNotNull(art.loopFor("Linkin Park", "Living Things", 400))
    }

    @Test
    fun nothingIsAskedWhileOff(): Unit = runBlocking {
        prefs = prefs.copy(enabled = false)
        assertNull(art().loopFor("Linkin Park", "Living Things", 400))
        prefs = prefs.copy(enabled = true, server = "")
        assertNull(art().loopFor("Linkin Park", "Living Things", 400))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun nothingIsKeptWithoutAFolder(): Unit = runBlocking {
        assertNull(art(withFolder = false).loopFor("Linkin Park", "Living Things", 400))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aServerUnderAPathKeepsItsPath() {
        val base = assertNotNull(artworkServer("https://example.com/artwork/"))
        val search = base.newBuilder().addPathSegments("api/v1/artwork/search").build()
        assertEquals("/artwork/api/v1/artwork/search", search.encodedPath)
        val bare = assertNotNull(artworkServer("example.com"))
        assertEquals("https://example.com/api/v1/artwork/search", bare.newBuilder().addPathSegments("api/v1/artwork/search").build().toString())
        assertEquals("http", artworkServer("http://192.168.1.20:8080")!!.scheme)
        assertNull(artworkServer("  "))
        assertNull(artworkServer("ftp://example.com"))
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
