package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import app.winters.octo.design.MenuRow
import app.winters.octo.design.OctoIcons
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.changesCover
import app.winters.octo.desktop.chooseCover
import app.winters.octo.desktop.hasOwnCover
import app.winters.octo.desktop.removeCover
import app.winters.octo.subsonic.Playlist

// The playlist menu's cover rows, for the listener's own playlists: pick a
// picture for its cover, or go back to the one made for it. False when none
// showed, so no separator is wanted.
@Composable
internal fun ColumnScope.PlaylistCoverRows(app: AppState, playlist: Playlist, close: () -> Unit): Boolean {
    if (!app.changesCover(playlist)) return false
    MenuRow("Change cover…", { close(); app.chooseCover(playlist) }, OctoIcons.Rename)
    if (app.hasOwnCover(playlist)) MenuRow("Remove cover", { close(); app.removeCover(playlist) }, OctoIcons.Close)
    return true
}
