package app.winters.octo.desktop.animated

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import app.winters.octo.animated.AnimatedArtSetting
import app.winters.octo.animated.onAlbumPage
import app.winters.octo.design.OctoIcons
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.settings.AnimatedGrids
import app.winters.octo.desktop.settings.animatedArtSetting
import app.winters.octo.desktop.ui.LocalWindowShown
import kotlinx.coroutines.delay

// The fork's animated covers outside the player and album pages: album
// grids (the tile under the pointer or the keyboard, or every tile in
// sight) and the mini player's large cover. Kept to this file so upstream's
// own pages change as little as they can.

// How long a tile is pointed at, or in sight, before its loop is asked
// for: long enough that sweeping or scrolling past asks for nothing.
private const val HoverSettleMs = 250L
private const val GridSettleMs = 600L

// How an album tile in a grid shows animated art just now.
class GridTile(val modifier: Modifier, val picture: (@Composable (Modifier, Shape) -> Unit)?)

// What an album tile needs for animated art: a modifier that notices the
// pointer and focus (in Hover), and the picture to draw instead of the
// still cover, or none when grids keep still covers.
@Composable
fun rememberGridTile(app: AppState, albumId: String, coverId: String?, online: Boolean, modifier: Modifier): GridTile {
    val settings by app.settings.state.collectAsState()
    val setting = settings.appearance.animatedArtSetting
    val grids = if (setting == AnimatedArtSetting.Off) AnimatedGrids.Off else settings.appearance.animatedGrids
    if (grids == AnimatedGrids.Off) return GridTile(modifier, null)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val wanted = grids == AnimatedGrids.Always || hovered || focused
    var live by remember(albumId) { mutableStateOf(false) }
    LaunchedEffect(wanted, albumId) {
        if (!wanted) {
            live = false
            return@LaunchedEffect
        }
        delay(if (grids == AnimatedGrids.Always) GridSettleMs else HoverSettleMs)
        live = true
    }
    val tracked = if (grids == AnimatedGrids.Hover) modifier.hoverable(hover).onFocusChanged { focused = it.hasFocus } else modifier
    return GridTile(tracked) { picture, shape ->
        val still: @Composable (Modifier) -> Unit = { Cover(coverId, it, shape = shape, online = online, placeholder = OctoIcons.Album) }
        if (!live) {
            still(picture)
        } else {
            val art by app.animated.artOf(albumId)
            AnimatedCover(
                app.animated,
                albumId,
                setting.onAlbumPage(art),
                seen = LocalWindowShown.current && !app.fullPlayer,
                shape = shape,
                modifier = picture,
                still = still,
            )
        }
    }
}

// The mini player's large cover: the playing album's square loop over its
// cover, where it has one and Animated art is on.
@Composable
fun MiniPlayerCover(app: AppState, albumId: String?, coverId: String?, seen: Boolean, shape: Shape, modifier: Modifier) {
    val settings by app.settings.state.collectAsState()
    val setting = settings.appearance.animatedArtSetting
    val art = if (setting == AnimatedArtSetting.Off) null else app.animated.artOf(albumId).value
    AnimatedCover(app.animated, albumId, setting.onAlbumPage(art), seen, shape, modifier) {
        Cover(coverId, it, shape = shape, placeholder = OctoIcons.Songs, retry = true)
    }
}
