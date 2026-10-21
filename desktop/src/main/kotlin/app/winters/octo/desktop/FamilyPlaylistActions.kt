package app.winters.octo.desktop

import app.winters.octo.desktop.server.userMessage
import app.winters.octo.subsonic.FamilyPerson
import app.winters.octo.subsonic.FamilyPlaylistShare
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.createFamilyPlaylist
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.launch

// Family playlists with chosen people: who one is shared with, and making a
// family playlist from one of the listener's own. Both through the family
// page's API, signed as every call is (an Octo server with Family on).

// The owner Octo gives family playlists in playlist lists.
const val FAMILY_PLAYLIST_OWNER = "Family"

// Whether a playlist is one of the family's.
fun Playlist.isFamily(): Boolean = owner.equals(FAMILY_PLAYLIST_OWNER, ignoreCase = true)

// Whether the listener may make family playlists: Family on and the plan
// lets them add to family playlists.
fun AppState.makesFamilyPlaylists(): Boolean =
    connection?.family == true && family.me?.abilities?.familyPlaylistsEdit == true

// A family playlist's sharing as the server keeps it, or null when it
// cannot be read (Family off, or a server without it).
suspend fun AppState.familyShareOf(id: String): FamilyPlaylistShare? {
    val client = connection?.client ?: return null
    return runCatching { client.familyWeb().playlists() }.getOrNull()?.firstOrNull { it.id == id }
}

// Everyone a family playlist can be shared with, the owner first.
suspend fun AppState.familyPeople(): List<FamilyPerson> {
    val client = connection?.client ?: return emptyList()
    return runCatching { client.familyWeb().playlistPeople() }.getOrNull().orEmpty().sortedByDescending { it.owner }
}

// Who sees a family playlist, in words: "the whole family", or the people
// it is shared with by name.
fun familyShareLine(share: FamilyPlaylistShare, people: List<FamilyPerson>): String {
    if (share.sharedWith.isEmpty()) return "Shared with the whole family"
    val names = share.sharedWith.map { username -> people.firstOrNull { it.username.equals(username, ignoreCase = true) }?.name ?: username }
    return "Shared with " + when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

// Counts sharing changes made here, so pages showing who sees a playlist
// read it again.
val familySharesChanged = mutableIntStateOf(0)

// Who sees a family playlist, in words, as the page under its name says
// it; null for other playlists and until read.
@Composable
fun AppState.familyShareLine(playlist: Playlist): String? {
    if (!playlist.isFamily()) return null
    val changed by familySharesChanged
    val line by produceState<String?>(null, playlist.id, changed) {
        val share = familyShareOf(playlist.id) ?: return@produceState
        value = familyShareLine(share, if (share.sharedWith.isEmpty()) emptyList() else familyPeople())
    }
    return line
}

// Shares a family playlist with these people (usernames) besides its maker
// and the owner, or with the whole family when none.
fun AppState.shareFamilyPlaylist(id: String, people: List<String>, done: () -> Unit = {}) {
    val client = connection?.client ?: return
    scope.launch {
        try {
            client.familyWeb().sharePlaylist(id, people)
            familySharesChanged.intValue++
            notice = if (people.isEmpty()) "Shared with the whole family" else "Sharing changed"
            refreshPlaylists()
            done()
        } catch (e: SubsonicException) {
            notice = "Couldn't change who it's shared with: ${e.userMessage()}"
        }
    }
}

// Makes a family playlist of one of the listener's own: the same songs in
// the same order, shared with `people` (none: the whole family). The
// original stays unless `deleteOriginal`, and only once the copy is whole.
fun AppState.makeFamilyPlaylist(playlist: Playlist, name: String, people: List<String>, deleteOriginal: Boolean) {
    val client = connection?.client ?: return
    scope.launch {
        val made = try {
            val songs = playlistSongs(playlist.id)
            val made = client.createFamilyPlaylist(name.trim().ifEmpty { playlist.name })
            if (songs.isNotEmpty()) client.updatePlaylist(made.id, songIdsToAdd = songs.map { it.id }, formPost = formPost)
            if (people.isNotEmpty()) client.familyWeb().sharePlaylist(made.id, people)
            made
        } catch (e: SubsonicException) {
            notice = "Couldn't make the family playlist: ${e.userMessage()}"
            refreshPlaylists()
            return@launch
        }
        notice = "Made the family playlist ${made.name}"
        refreshPlaylists()
        if (deleteOriginal) deletePlaylist(playlist)
    }
}
