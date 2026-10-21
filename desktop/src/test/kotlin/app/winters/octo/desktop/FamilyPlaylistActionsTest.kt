package app.winters.octo.desktop

import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.subsonic.FamilyPerson
import app.winters.octo.subsonic.FamilyPlaylistShare
import app.winters.octo.subsonic.Playlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Family playlists for chosen people, against a pretend Octo server.
class FamilyPlaylistActionsTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun app(): AppState {
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        return AppState(
            settings,
            Accounts(settings, SessionOnlySecrets(), http),
            http,
            scope,
            DesktopOs.Windows,
            SilentPlayer(),
            OnlineLyrics(http, server.address.toHttpUrl()),
            restored = server.connection(),
        )
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(what())
    }

    private fun callsTo(endpoint: String) = server.calls.filter { it.url.pathSegments.last() == endpoint }

    private fun json(body: String) = MockResponse.Builder().body(body).build()

    private val mine = Playlist("pl1", "Road trip", owner = "winters")

    @Test
    fun aPlaylistIsCopiedToTheFamilyInOrderAndSharedWithThoseChosen() {
        server.answer("getPlaylist", """"playlist":{"id":"pl1","name":"Road trip","entry":[${songJson("s1", "One")},${songJson("s2", "Two")}]}""")
        server.answer("createFamilyPlaylist", """"playlist":{"id":"of123","name":"Road trip","owner":"Family"}""")
        server.answer("updatePlaylist")
        server.respondBy("of123") { json("""{"Id":"of123"}""") }
        val app = app()
        app.makeFamilyPlaylist(mine, "Road trip", listOf("sam"), deleteOriginal = false)
        waitFor { app.notice != null }

        assertEquals("Made the family playlist Road trip", app.notice)
        assertEquals("Road trip", callsTo("createFamilyPlaylist").single().url.queryParameter("name"))
        val added = callsTo("updatePlaylist").single().url
        assertEquals("of123", added.queryParameter("playlistId"))
        assertEquals(listOf("s1", "s2"), added.queryParameterValues("songIdToAdd"))
        val shared = callsTo("of123").single()
        assertEquals("PUT", shared.method)
        assertEquals("/api/family/playlists/of123", shared.url.encodedPath)
        assertEquals("""{"sharedWith":["sam"]}""", shared.body!!.utf8())
        // Signed as every call is.
        assertEquals("winters", shared.url.queryParameter("u"))
        assertTrue(callsTo("deletePlaylist").isEmpty())
    }

    @Test
    fun theOriginalGoesOnlyWhenAskedAndOnlyOnceTheCopyIsMade() {
        server.answer("getPlaylist", """"playlist":{"id":"pl1","name":"Road trip","entry":[${songJson("s1", "One")}]}""")
        server.answer("createFamilyPlaylist", """"playlist":{"id":"of9","name":"Ours"}""")
        server.answer("updatePlaylist")
        server.answer("deletePlaylist")
        val app = app()
        app.makeFamilyPlaylist(mine, "Ours", emptyList(), deleteOriginal = true)
        waitFor { callsTo("deletePlaylist").isNotEmpty() }
        assertEquals("pl1", callsTo("deletePlaylist").single().url.queryParameter("id"))
        // Shared with the whole family: no sharing call at all.
        assertTrue(callsTo("of9").isEmpty())
    }

    @Test
    fun aCopyTheServerRefusesKeepsTheOriginal() {
        server.answer("getPlaylist", """"playlist":{"id":"pl1","name":"Road trip","entry":[]}""")
        server.answerBy("createFamilyPlaylist") { """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":50,"message":"This account cannot change family playlists."}}}""" }
        server.answer("deletePlaylist")
        val app = app()
        app.makeFamilyPlaylist(mine, "Ours", emptyList(), deleteOriginal = true)
        waitFor { app.notice != null }
        assertTrue(app.notice!!.startsWith("Couldn't make the family playlist"))
        assertTrue(callsTo("deletePlaylist").isEmpty())
    }

    @Test
    fun sharingSendsThePeopleAndNoneMeansEveryone() {
        server.respondBy("of1") { json("""{"Id":"of1"}""") }
        val app = app()
        app.shareFamilyPlaylist("of1", emptyList())
        waitFor { app.notice != null }
        assertEquals("""{"sharedWith":[]}""", callsTo("of1").single().body!!.utf8())
        assertEquals("Shared with the whole family", app.notice)
    }

    @Test
    fun whoSeesItReadsAsWords() {
        val people = listOf(FamilyPerson("sam", "Sam"), FamilyPerson("kid", "Kid"), FamilyPerson("alex", ""))
        assertEquals("Shared with the whole family", familyShareLine(FamilyPlaylistShare(sharedWith = emptyList()), people))
        assertEquals("Shared with Sam", familyShareLine(FamilyPlaylistShare(sharedWith = listOf("SAM")), people))
        assertEquals("Shared with Sam and Kid", familyShareLine(FamilyPlaylistShare(sharedWith = listOf("sam", "kid")), people))
        assertEquals("Shared with Sam, Kid and alex", familyShareLine(FamilyPlaylistShare(sharedWith = listOf("sam", "kid", "alex")), people))
    }

    @Test
    fun onlyFamilyOwnedPlaylistsAreTheFamilys() {
        assertTrue(Playlist("of1", "Ours", owner = "Family").isFamily())
        assertFalse(mine.isFamily())
    }
}
