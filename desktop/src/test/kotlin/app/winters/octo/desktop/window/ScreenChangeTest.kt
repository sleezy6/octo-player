package app.winters.octo.desktop.window

import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenChangeTest {
    private val laptop = Rectangle(1920, 0, 1280, 680) // 1920x1080 at 150%, less the taskbar
    private val least = Dimension(960, 600)

    @Test
    fun theOldSizeComesBackWhereItFits() {
        val grown = Rectangle(2000, 20, 2400, 1500)
        assertEquals(Rectangle(2000, 20, 1100, 650), fitOnScreen(grown, Dimension(1100, 650), laptop, least))
    }

    @Test
    fun aWindowBiggerThanTheScreenIsCutToItAndMovedOnto() {
        val grown = Rectangle(2500, 100, 2400, 1500)
        assertEquals(Rectangle(1920, 0, 1280, 680), fitOnScreen(grown, Dimension(1600, 1000), laptop, least))
    }

    @Test
    fun aWindowIsMovedBackOntoItsScreen() {
        val hanging = Rectangle(2800, 300, 1000, 650)
        assertEquals(Rectangle(2200, 30, 1000, 650), fitOnScreen(hanging, Dimension(1000, 650), laptop, least))
    }

    @Test
    fun itsLeastWinsOnATinyScreen() {
        val tiny = Rectangle(0, 0, 900, 500)
        assertEquals(Rectangle(0, 0, 960, 600), fitOnScreen(Rectangle(0, 0, 1600, 1000), Dimension(1600, 1000), tiny, least))
    }

    @Test
    fun aResizeMomentsBeforeTheScreenChangeIsTheSystemsNotTheListeners() {
        val sizes = SteadySize(Dimension(1600, 1000))
        sizes.saw(Dimension(1200, 800), at = 1_000)
        // The system grows it for the new screen, then the window says so.
        sizes.saw(Dimension(1800, 1200), at = 5_000)
        assertEquals(Dimension(1200, 800), sizes.before(at = 5_050))
        // Long after, the latest size is the listener's own.
        assertEquals(Dimension(1800, 1200), sizes.before(at = 9_000))
    }

    @Test
    fun draggingAnEdgeKeepsTheSizeFromBeforeTheDrag() {
        val sizes = SteadySize(Dimension(1000, 700))
        for (step in 0..10) sizes.saw(Dimension(1000 + step * 10, 700), at = 1_000L + step * 16)
        assertEquals(Dimension(1100, 700), sizes.before(at = 5_000))
    }
}

class FollowPlaceTest {
    @Test
    fun theWindowMovesAsFarAsThePointer() {
        assertEquals(java.awt.Point(130, 220), followPlace(java.awt.Point(100, 200), java.awt.Point(500, 500), java.awt.Point(530, 520), 1.0))
    }

    @Test
    fun aPointerOnAScreenOfAnotherScaleMovesItInTheWindowsUnits() {
        // The pointer on a 150% screen, the window still on a 100% one.
        assertEquals(java.awt.Point(145, 200), followPlace(java.awt.Point(100, 200), java.awt.Point(0, 0), java.awt.Point(30, 0), 1.5))
    }
}
