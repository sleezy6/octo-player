package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoIcons
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.familyPeople
import app.winters.octo.desktop.familyShareOf
import app.winters.octo.desktop.isFamily
import app.winters.octo.desktop.makeFamilyPlaylist
import app.winters.octo.desktop.makesFamilyPlaylists
import app.winters.octo.desktop.shareFamilyPlaylist
import app.winters.octo.subsonic.FamilyPerson
import app.winters.octo.subsonic.FamilyPlaylistShare
import app.winters.octo.subsonic.Playlist

// The playlist menu's family rows and pages: "Shared with…" on a family
// playlist its maker or the owner opens, and "Make a family playlist…" on
// one of the listener's own.

// Which family page a playlist menu shows, if any.
internal enum class FamilyMenuPage { Share, Make }

// The family rows for a playlist's menu, when there are any: false when
// none showed, so no separator is wanted.
@Composable
internal fun ColumnScope.FamilyPlaylistRows(app: AppState, playlist: Playlist, owns: Boolean, open: (FamilyMenuPage) -> Unit): Boolean {
    if (playlist.isFamily()) {
        val share by produceState<FamilyPlaylistShare?>(null, playlist.id) { value = app.familyShareOf(playlist.id) }
        val mine = share?.takeIf { it.mayShare } ?: return false
        MenuRow("Shared with…", { open(FamilyMenuPage.Share) }, OctoIcons.Share, more = true, detail = if (mine.sharedWith.isEmpty()) "Everyone" else "${mine.sharedWith.size}")
        return true
    }
    if (owns && app.makesFamilyPlaylists()) {
        MenuRow("Make a family playlist…", { open(FamilyMenuPage.Make) }, OctoIcons.AddToPlaylist, more = true)
        return true
    }
    return false
}

// Choosing who a family playlist is shared with.
@Composable
internal fun ColumnScope.FamilyShareMenu(app: AppState, playlist: Playlist, close: () -> Unit, back: () -> Unit) {
    val share by produceState<FamilyPlaylistShare?>(null, playlist.id) { value = app.familyShareOf(playlist.id) }
    val people by produceState<List<FamilyPerson>?>(null) { value = app.familyPeople() }
    MenuRow("Back", back, OctoIcons.Back)
    MenuSeparator()
    MenuTitle("Who sees ${playlist.name}")
    val known = share
    val everyone = people
    if (known == null || everyone == null) {
        MenuRow("Reading the family…", {}, enabled = false)
        return
    }
    var chosen by remember(known.id) { mutableStateOf(known.sharedWith.map(String::lowercase).toSet()) }
    PeopleRows(everyone, known.createdBy, chosen) { chosen = it }
    MenuSeparator()
    MenuRow("Save", { app.shareFamilyPlaylist(playlist.id, picked(everyone, chosen)); close() }, OctoIcons.Check)
}

// Making a family playlist of one of the listener's own: its name, who sees
// it, and whether the original goes once the copy is made.
@Composable
internal fun ColumnScope.MakeFamilyPlaylistMenu(app: AppState, playlist: Playlist, close: () -> Unit, back: () -> Unit) {
    val people by produceState<List<FamilyPerson>?>(null) { value = app.familyPeople() }
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    var chosen by remember(playlist.id) { mutableStateOf(emptySet<String>()) }
    var deleteOriginal by remember(playlist.id) { mutableStateOf(false) }
    MenuRow("Back", back, OctoIcons.Back)
    MenuSeparator()
    MenuTitle("Who sees it")
    val everyone = people
    if (everyone == null) {
        MenuRow("Reading the family…", {}, enabled = false)
    } else {
        PeopleRows(everyone, app.connection?.client?.username.orEmpty(), chosen) { chosen = it }
    }
    MenuSeparator()
    MenuRow("Delete my playlist afterwards", { deleteOriginal = !deleteOriginal }, OctoIcons.Delete, checked = deleteOriginal)
    MenuSeparator()
    val make = {
        if (name.isNotBlank()) {
            app.makeFamilyPlaylist(playlist, name, picked(everyone.orEmpty(), chosen), deleteOriginal)
            close()
        }
    }
    NameField(name, { name = it }, "Family playlist name", "Make", make, back)
}

// The family as ticks: the whole family when nobody is picked, else the
// people picked. The maker and the owner always see it, so they show
// ticked and cannot be changed.
@Composable
private fun ColumnScope.PeopleRows(people: List<FamilyPerson>, maker: String, chosen: Set<String>, change: (Set<String>) -> Unit) {
    MenuRow("Everyone in the family", { change(emptySet()) }, OctoIcons.Artist, checked = chosen.isEmpty())
    people.forEach { person ->
        val key = person.username.lowercase()
        val always = person.owner || key == maker.lowercase()
        MenuRow(
            person.name,
            { change(if (key in chosen) chosen - key else chosen + key) },
            OctoIcons.Artist,
            enabled = !always,
            detail = when {
                person.owner -> "Owner, always"
                always -> "Made it, always"
                else -> null
            },
            checked = always || key in chosen,
        )
    }
}

// The usernames picked, as the server named them; the owner, who always
// sees it, left out.
private fun picked(people: List<FamilyPerson>, chosen: Set<String>): List<String> =
    people.filter { !it.owner && it.username.lowercase() in chosen }.map { it.username }
