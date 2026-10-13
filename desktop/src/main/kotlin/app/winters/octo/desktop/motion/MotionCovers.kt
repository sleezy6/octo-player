package app.winters.octo.desktop.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import app.winters.octo.audio.MotionCover
import app.winters.octo.design.ArtworkShape
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.artworkRim
import app.winters.octo.design.motionScale
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.settings.MotionCoverPrefs
import app.winters.octo.desktop.ui.LocalWindowShown
import app.winters.octo.player.immersive.CoverFadeMs
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.AlbumWithSongs
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Animated covers for the whole app: finding and keeping them (MotionArt),
// and the loops playing now, each decoded once however many places show
// it (the full player and the mini player at once, say).
class MotionCovers(http: OkHttpClient, val prefs: StateFlow<MotionCoverPrefs>, private val scope: CoroutineScope) {
    val art = MotionArt(http, { prefs.value })

    private val playing = ConcurrentHashMap<File, Flow<Image>>()

    // The pictures of a loop, in time, while anything watches. A loop
    // nobody has watched for a moment stops, and starts from its first
    // picture when watched again. Each picture stays usable until a few
    // newer ones have come, so a place still drawing the last is safe.
    fun frames(file: File): Flow<Image> = playing.getOrPut(file) {
        loopFrames(file, scope).shareIn(
            scope,
            // Stopped, it keeps no picture to hand the next watcher: those
            // pictures are freed.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = STOP_AFTER_MS, replayExpirationMillis = 0),
            replay = 1,
        )
    }

    companion object {
        const val STOP_AFTER_MS = 2_000L
    }
}

// How many pictures of a loop are kept before the oldest is freed: enough
// that one shown a few frames ago is never drawn after it is gone.
private const val KEPT_PICTURES = 4

// A behind that is longer than this starts the clock again rather than
// rushing pictures out to catch up (after the computer slept, say).
private const val CATCH_UP_LIMIT_NS = 500_000_000L

private fun loopFrames(file: File, scope: CoroutineScope): Flow<Image> = flow {
    MotionCover.open(file.path).use { cover ->
        val kept = ArrayDeque<Image>()
        try {
            var due = System.nanoTime()
            while (true) {
                val frame = cover.nextFrame()
                val width = frame.width.toInt()
                val height = frame.height.toInt()
                val image = Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), frame.pixels, width * 4)
                val wait = due - System.nanoTime()
                if (wait > 0) delay(wait / 1_000_000) else if (-wait > CATCH_UP_LIMIT_NS) due = System.nanoTime()
                emit(image)
                kept.addLast(image)
                while (kept.size > KEPT_PICTURES) kept.removeFirst().close()
                due += frame.durationUs.toLong() * 1_000
            }
        } finally {
            // Whoever showed the last ones has let go by now.
            val left = kept.toList()
            scope.launch {
                delay(1_000)
                left.forEach(Image::close)
            }
        }
    }
}.catch { error ->
    // A loop that will not play leaves the still cover showing.
    System.err.println("Animated cover ${file.name} not shown: ${error.message}")
}.flowOn(Dispatchers.Default)

// Where the app's animated covers come from; none in the tests and
// screenshots, which then show still covers only.
val LocalMotionCovers = staticCompositionLocalOf<MotionCovers?> { null }

// The sizes Apple keeps loops at. A box is asked for at the first that
// covers it, so a window being resized does not ask again at every pixel.
private val LoopSizes = listOf(360, 486, 768, 960, 1080)

internal fun loopSize(px: Int): Int = LoopSizes.firstOrNull { it >= px } ?: LoopSizes.last()

// Who an album is by, as the artwork server should be asked: the album's
// own artist where the song says it, not a featured one.
fun Song.motionArtist(): String? =
    (displayAlbumArtist ?: albumArtists.firstOrNull()?.name ?: displayArtist ?: artist)?.takeIf(String::isNotBlank)

fun AlbumWithSongs.motionArtist(): String? = (displayArtist ?: artist).takeIf(String::isNotBlank)

fun Album.motionArtist(): String? = (displayArtist ?: artist).takeIf(String::isNotBlank)

// A cover that moves where its album has an animated one: the still cover
// as always, with the loop fading in over it once ready. Still when the
// setting is off, motion is reduced, or the window is out of sight.
// `playing` off holds it still (an album grid's tile not under the
// pointer): a loop showing fades back out, and none is looked up. A tile
// waits `settleMs` before asking, so the pointer sweeping across a grid,
// or a grid scrolling past, asks about none of what it passes.
@Composable
fun MotionCoverArt(
    coverId: String?,
    artist: String?,
    album: String?,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
    online: Boolean = false,
    placeholder: ImageVector = OctoIcons.Album,
    retry: Boolean = false,
    playing: Boolean = true,
    settleMs: Long = 0,
) {
    Box(modifier) {
        Cover(coverId, Modifier.fillMaxSize(), shape = shape, online = online, placeholder = placeholder, retry = retry)
        val covers = LocalMotionCovers.current
        if (covers != null && artist != null && album != null) {
            // Kept a moment after playing stops, while the loop fades out.
            var fading by remember { mutableStateOf(false) }
            LaunchedEffect(playing) { if (playing) fading = true }
            if (playing || fading) Loop(covers, artist, album, shape, playing, settleMs) { fading = false }
        }
    }
}

@Composable
private fun Loop(covers: MotionCovers, artist: String, album: String, shape: Shape, playing: Boolean, settleMs: Long, gone: () -> Unit) {
    val prefs by covers.prefs.collectAsState()
    val still = LocalReduceMotion.current || !LocalWindowShown.current
    if (!prefs.enabled || prefs.server.isBlank() || still || album.isBlank()) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val loopPx = loopSize(with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() })
        // Everything below starts afresh for another album, so the last
        // album's loop never shows while the next one's is found.
        key(artist, album, loopPx, prefs.server) {
            val file by produceState<File?>(null) {
                if (settleMs > 0) delay(settleMs)
                value = covers.art.loopFor(artist, album, loopPx)
            }
            val loop = file
            if (loop != null) {
                Playing(covers, loop, shape, playing, gone)
            } else if (!playing) {
                // Stopped before anything showed: nothing to fade.
                SideEffect(gone)
            }
        }
    }
}

@Composable
private fun Playing(covers: MotionCovers, loop: File, shape: Shape, playing: Boolean, gone: () -> Unit) {
    val picture by produceState<Image?>(null) { covers.frames(loop).collect { value = it } }
    val shown by animateFloatAsState(
        if (picture != null && playing) 1f else 0f,
        tween(motionScale().ms(CoverFadeMs.toInt())),
        label = "animated cover",
        finishedListener = { if (it == 0f) gone() },
    )
    val image = picture
    if (image == null) {
        if (!playing) SideEffect(gone)
        return
    }
    Canvas(Modifier.fillMaxSize().clip(shape).graphicsLayer { alpha = shown }) {
        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawImageRect(image, centreSquare(image), Rect.makeWH(size.width, size.height), Smooth, null, true) }
    }
    Box(Modifier.fillMaxSize().artworkRim(shape).graphicsLayer { alpha = shown })
}

private val Smooth = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

// The middle of a picture that is not square, as a cover crops it.
private fun centreSquare(image: Image): Rect {
    val side = minOf(image.width, image.height).toFloat()
    val left = (image.width - side) / 2
    val top = (image.height - side) / 2
    return Rect.makeXYWH(left, top, side, side)
}
