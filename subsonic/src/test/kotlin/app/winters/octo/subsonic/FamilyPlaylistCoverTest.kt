package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FamilyPlaylistCoverTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("alex", "secret"), OkHttpClient())

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    @Test
    fun thePictureGoesRawWithTheSignInInTheAddress() = runTest {
        answer("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","familyPlaylistCover":{"id":"of1","coverArt":"of1_3fa9c1"}}}""")
        val made = client().setFamilyPlaylistCover("of1", byteArrayOf(9, 8, 7))
        assertEquals("of1_3fa9c1", made.coverArt)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/rest/setFamilyPlaylistCover", sent.url.encodedPath)
        assertEquals("of1", sent.url.queryParameter("id"))
        assertEquals("alex", sent.url.queryParameter("u"))
        assertEquals("image/jpeg", sent.headers["Content-Type"])
        assertTrue(sent.body!!.toByteArray().contentEquals(byteArrayOf(9, 8, 7)))
    }

    @Test
    fun removingAnswersThePaintedCover() = runTest {
        answer("""{"subsonic-response":{"status":"ok","version":"1.16.1","familyPlaylistCover":{"id":"of1","coverArt":"of1_00aa"}}}""")
        assertEquals("of1_00aa", client().deleteFamilyPlaylistCover("of1").coverArt)
        assertEquals("/rest/deleteFamilyPlaylistCover", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aRefusedPictureSaysWhy() = runTest {
        answer("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":0,"message":"That picture is smaller than 100 pixels on a side."}}}""")
        try {
            client().setFamilyPlaylistCover("of1", byteArrayOf(1))
            fail("refused")
        } catch (e: SubsonicException) {
            assertTrue(e.message!!.contains("100 pixels"))
        }
    }
}
