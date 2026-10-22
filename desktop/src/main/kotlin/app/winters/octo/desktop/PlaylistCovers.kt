package app.winters.octo.desktop

import app.winters.octo.desktop.server.userMessage
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

// A playlist's own cover, uploaded to Navidrome (0.61 on) through its own
// API at the server's address; Octo passes it on. Only the listener's own
// playlists: Navidrome takes a cover only from a playlist's owner, and
// Octo's family playlists keep theirs on Octo.

// The picture types Navidrome takes as they are.
private val TAKEN = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp")

// Navidrome's usual limit is 10 MB; a picture over this, or wider than
// COVER_MOST_PX, goes as a JPEG of that size instead.
const val COVER_MOST_BYTES = 8L * 1024 * 1024
const val COVER_MOST_PX = 2000

// Whether the listener can change this playlist's cover here: their own,
// signed in with a password.
fun AppState.changesCover(playlist: Playlist): Boolean =
    canEdit(playlist) && !playlist.isFamily() && connection?.client?.navidrome?.usable == true

// Whether the playlist has a cover uploaded for it, which shows in place of
// a designed one.
fun AppState.hasOwnCover(playlist: Playlist): Boolean = playlist.id in ownCovers

// Asks for a picture, then uploads it as the playlist's cover.
fun AppState.chooseCover(playlist: Playlist) {
    scope.launch {
        // The file window holds the window's thread, so it opens after the menu has gone.
        val file = chooseImageFile(windows = os == DesktopOs.Windows) ?: return@launch
        setCover(playlist, file)
    }
}

fun AppState.setCover(playlist: Playlist, file: File) {
    val client = connection?.client ?: return
    scope.launch {
        val prepared = withContext(Dispatchers.IO) { runCatching { coverUpload(file) }.getOrNull() }
        if (prepared == null) {
            notice = "That file isn't a picture Octo can read."
            return@launch
        }
        try {
            client.navidrome.setPlaylistCover(playlist.id, prepared.bytes, prepared.type, prepared.name)
            ownCovers = ownCovers + playlist.id
            notice = "Changed the cover of ${playlist.name}"
            refreshPlaylists()
        } catch (e: SubsonicException) {
            notice = "Couldn't change the cover: ${e.userMessage()}"
        }
    }
}

// Back to the cover Octo or Navidrome makes for it.
fun AppState.removeCover(playlist: Playlist) {
    val client = connection?.client ?: return
    scope.launch {
        try {
            client.navidrome.removePlaylistCover(playlist.id)
            ownCovers = ownCovers - playlist.id
            notice = "Removed the cover of ${playlist.name}"
            refreshPlaylists()
        } catch (e: SubsonicException) {
            notice = "Couldn't remove the cover: ${e.userMessage()}"
        }
    }
}

// Which playlists have an uploaded cover, read again with the playlists.
// Quietly nothing when the server has none or this sign-in can't ask.
internal suspend fun AppState.readOwnCovers() {
    val native = connection?.client?.navidrome ?: return
    if (!native.usable || playlists.none(::canEdit)) {
        ownCovers = emptySet()
        return
    }
    runCatching { native.playlistsWithCovers() }.getOrNull()?.let { ownCovers = it }
}

// A picture ready to send: as it is when Navidrome takes it and it is
// small enough, otherwise a JPEG at most COVER_MOST_PX across.
class CoverUpload(val bytes: ByteArray, val type: String, val name: String)

fun coverUpload(file: File): CoverUpload? {
    val bytes = file.readBytes()
    val image = Image.makeFromEncoded(bytes)
    try {
        val type = TAKEN[file.extension.lowercase()]
        if (type != null && bytes.size <= COVER_MOST_BYTES && max(image.width, image.height) <= COVER_MOST_PX) {
            return CoverUpload(bytes, type, file.name)
        }
        return CoverUpload(shrunkJpeg(image, COVER_MOST_PX), "image/jpeg", file.nameWithoutExtension + ".jpg")
    } finally {
        image.close()
    }
}

// The picture as a JPEG no wider or taller than `most` pixels.
fun shrunkJpeg(image: Image, most: Int): ByteArray {
    val scale = minOf(1.0, most.toDouble() / max(image.width, image.height))
    val w = max(1, (image.width * scale).roundToInt())
    val h = max(1, (image.height * scale).roundToInt())
    val surface = Surface.makeRasterN32Premul(w, h)
    try {
        surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
        val shot = surface.makeImageSnapshot()
        try {
            return shot.encodeToData(EncodedImageFormat.JPEG, 90)?.bytes ?: error("no JPEG")
        } finally {
            shot.close()
        }
    } finally {
        surface.close()
    }
}

// The system's own window for picking a picture.
fun chooseImageFile(windows: Boolean): File? {
    val dialog = FileDialog(null as Frame?, "Choose a cover", FileDialog.LOAD)
    if (windows) dialog.file = "*.jpg;*.jpeg;*.png;*.gif;*.webp;*.bmp"
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in TAKEN.keys + "bmp" }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
