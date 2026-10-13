package app.winters.octo.desktop.motion

import app.winters.octo.desktop.settings.MotionCoverPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

// A loop playing through the audio engine's decoder into pictures: the
// engine's own test loop, 24 flat greys at 24 a second, picture n being
// luma 16 + 9n.
class MotionCoversTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val covers = MotionCovers(OkHttpClient(), MutableStateFlow(MotionCoverPrefs()), scope)

    @After
    fun close() = scope.cancel()

    private fun loop(): File {
        val source = File(System.getProperty("user.dir")).resolve("../audio-engine/tests/fixtures/gray-ramp-64-24f-high.mp4").canonicalFile
        return File(folder.root, "loop.mp4").also { source.copyTo(it) }
    }

    // The grey at a picture's middle, while it is still usable.
    private fun grey(image: Image): Int {
        val bitmap = Bitmap.makeFromImage(image)
        return bitmap.getColor(32, 32) shr 16 and 0xFF
    }

    @Test
    fun picturesComeInOrderAndInTime(): Unit = runBlocking {
        val started = System.nanoTime()
        val greys = mutableListOf<Int>()
        // 30 pictures: the 24 of the loop, then the first 6 again.
        covers.frames(loop()).take(30).collect { image ->
            assertEquals(64, image.width)
            greys += grey(image)
        }
        val seconds = (System.nanoTime() - started) / 1e9
        for ((n, got) in greys.withIndex()) {
            val wanted = (9 * (n % 24) * 255.0 / 219.0).roundToInt()
            assertTrue(abs(got - wanted) <= 4, "picture $n: grey $got, wanted about $wanted")
        }
        // 29 gaps of a 24th of a second after the first picture.
        assertTrue(seconds in 1.1..1.6, "30 pictures took $seconds s")
    }

    @Test
    fun twoPlacesShareOneLoop(): Unit = runBlocking {
        val file = loop()
        val first = async { covers.frames(file).take(6).toList() }
        val second = async { covers.frames(file).take(6).toList() }
        val a = first.await()
        val b = second.await()
        // The same pictures, decoded once: whichever joined second may have
        // missed the very first.
        val shared = a.intersect(b.toSet())
        assertTrue(shared.size >= 5, "only ${shared.size} pictures shared")
        assertSame(covers.frames(file), covers.frames(file))
    }

    @Test
    fun aDamagedLoopEndsWithoutPictures(): Unit = runBlocking {
        val file = File(folder.root, "broken.mp4").apply { writeBytes(ByteArray(500) { it.toByte() }) }
        assertEquals(emptyList(), covers.frames(file).toListWithin(2_000))
    }

    @Test
    fun loopSizesFollowApplesCopies() {
        assertEquals(360, loopSize(100))
        assertEquals(486, loopSize(400))
        assertEquals(768, loopSize(768))
        assertEquals(1080, loopSize(1000))
        assertEquals(1080, loopSize(4000))
    }
}

// What a shared flow gives in `ms`: a stopped loop never completes on its own.
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.toListWithin(ms: Long): List<T> {
    val got = mutableListOf<T>()
    kotlinx.coroutines.withTimeoutOrNull(ms) { collect { got += it } }
    return got
}
