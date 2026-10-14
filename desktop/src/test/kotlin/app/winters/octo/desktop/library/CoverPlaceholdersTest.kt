package app.winters.octo.desktop.library

import app.winters.octo.desktop.FakeServer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// Navidrome's stand-in for a missing cover is never kept and never shown:
// marked no-store (Navidrome 0.64 on) or recognised by its bytes (before),
// it counts as a cover that did not come, and one kept before is let go.
// The stand-in here is Navidrome's own file (GPL-3.0, as Octo is), its
// December 2024 version, from navidrome/navidrome resources/.
class CoverPlaceholdersTest {
    @get:Rule val folder = TemporaryFolder()

    private val placeholder: ByteArray =
        javaClass.getResourceAsStream("/covers/navidrome-placeholder-2024.webp")!!.use { it.readBytes() }

    private fun square(px: Int): ByteArray = Surface.makeRasterN32Premul(px, px).run {
        canvas.clear(0xFF2060D0.toInt())
        makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    @After
    fun calm() = CoverTraffic.lanes.setUrgent(emptyList())

    // What the server answers next: the stand-in, marked or not, or the cover.
    private enum class Answer { MarkedStandIn, UnmarkedStandIn, Cover }

    private fun covers(test: (FakeServer, ImageLoader, () -> Unit, (Answer) -> Unit, MutableList<Int>) -> Unit) {
        val asked = CopyOnWriteArrayList<Int>()
        var answer = Answer.MarkedStandIn
        val cache = folder.newFolder()
        FakeServer().use { server ->
            server.respondBy("getCoverArt") { request ->
                val size = request.url.queryParameter("size")!!.toInt()
                asked += size
                when (answer) {
                    Answer.MarkedStandIn -> MockResponse.Builder()
                        .addHeader("Content-Type", "image/webp")
                        .addHeader("Cache-Control", "no-store")
                        .body(okio.Buffer().write(placeholder))
                        .build()
                    Answer.UnmarkedStandIn -> MockResponse.Builder()
                        .addHeader("Content-Type", "image/webp")
                        .body(okio.Buffer().write(placeholder))
                        .build()
                    Answer.Cover -> MockResponse.Builder()
                        .addHeader("Content-Type", "image/png")
                        .addHeader("Cache-Control", "public, no-cache")
                        .body(okio.Buffer().write(square(size)))
                        .build()
                }
            }
            val loader = coverLoader(PlatformContext.INSTANCE, OkHttpClient(), cache)
            test(server, loader, { loader.memoryCache?.clear() }, { answer = it }, asked)
        }
    }

    private fun ImageLoader.load(server: FakeServer, id: String, bucket: Int): ImageResult = runBlocking {
        execute(coverRequest(PlatformContext.INSTANCE, server.client(), id, bucket).size(bucket).build())
    }

    private fun ImageLoader.kept(server: FakeServer, id: String, bucket: Int): Boolean {
        val disk = diskCache ?: return false
        return disk.openSnapshot(coverKey(server.client().primaryUrl.host, id, bucket))?.use { true } ?: false
    }

    @Test
    fun aMarkedStandInIsNeitherShownNorKept() = covers { server, loader, _, _, _ ->
        val result = loader.load(server, "al-1", 600)
        assertTrue("the stand-in is a cover that did not come", result is ErrorResult)
        assertTrue((result as ErrorResult).throwable is PlaceholderCover)
        assertFalse("nothing kept on disk", loader.kept(server, "al-1", 600))
        assertNull("nothing kept in memory", loader.memoryCache?.get(coil3.memory.MemoryCache.Key(coverKey(server.client().primaryUrl.host, "al-1", 600))))
    }

    @Test
    fun anUnmarkedStandInIsKnownByItsBytes() = covers { server, loader, _, answer, _ ->
        answer(Answer.UnmarkedStandIn)
        val result = loader.load(server, "al-1", 600)
        assertTrue(result is ErrorResult)
        assertTrue((result as ErrorResult).throwable is PlaceholderCover)
    }

    @Test
    fun onceTheArtIsReadTheCoverComesAndIsKept() = covers { server, loader, _, answer, asked ->
        assertTrue(loader.load(server, "al-1", 600) is ErrorResult)
        answer(Answer.Cover)
        val result = loader.load(server, "al-1", 600)
        assertTrue("asked again, not answered from a kept stand-in", result is SuccessResult)
        assertEquals(600, (result as SuccessResult).image.width)
        assertTrue(loader.kept(server, "al-1", 600))
        assertEquals(listOf(600, 600), asked)
    }

    // A stand-in kept by an Octo from before this, or from a Navidrome that
    // did not mark it: let go when found, and the cover asked for again.
    @Test
    fun aStandInKeptBeforeIsLetGoAndTheCoverFetched() = covers { server, loader, forget, answer, asked ->
        val key = coverKey(server.client().primaryUrl.host, "al-1", 1200)
        val disk = loader.diskCache!!
        disk.openEditor(key)!!.apply {
            disk.fileSystem.write(data) { write(placeholder) }
            commit()
        }
        forget()
        answer(Answer.Cover)
        val result = loader.load(server, "al-1", 600)
        assertTrue(result is SuccessResult)
        assertEquals(DataSource.NETWORK, (result as SuccessResult).dataSource)
        assertFalse("the kept stand-in is gone", loader.kept(server, "al-1", 1200))
        assertEquals(listOf(600), asked)
    }

    @Test
    fun aRealCoverIsStillKeptAsBefore() = covers { server, loader, forget, answer, asked ->
        answer(Answer.Cover)
        assertTrue(loader.load(server, "al-1", 300) is SuccessResult)
        forget()
        val again = loader.load(server, "al-1", 300)
        assertEquals(DataSource.DISK, (again as SuccessResult).dataSource)
        assertEquals(listOf(300), asked)
    }

    @Test
    fun onlyTheStandInsBytesMatch() {
        assertTrue(NavidromePlaceholder.matches(placeholder))
        assertFalse(NavidromePlaceholder.matches(placeholder.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }))
        assertFalse(NavidromePlaceholder.matches(square(64)))
    }

    @Test
    fun noStoreIsReadWhereverItIsWritten() {
        assertTrue(noStore(listOf("no-store")))
        assertTrue(noStore(listOf("private, No-Store")))
        assertTrue(noStore(listOf("public", "no-store")))
        assertFalse(noStore(listOf("public, no-cache")))
        assertFalse(noStore(listOf("public, max-age=31536000, immutable")))
        assertFalse(noStore(emptyList()))
    }

    @Test
    fun theFixtureIsTheShippedFile() {
        assertEquals(17_464, placeholder.size)
        assertTrue(File("src/test/resources/covers/navidrome-placeholder-2024.webp").isFile)
    }
}
