package app.winters.octo.design

import org.junit.Assert.assertEquals
import org.junit.Test

// How a row of choices shares its width: evenly while every label fits so,
// each to its own label when one long label would be cut short.
class TabWidthsTest {
    private fun assertWidths(expected: List<Float>, actual: FloatArray) =
        assertEquals(expected, actual.map { Math.round(it * 10) / 10f })

    @Test
    fun evenWhileEveryLabelFits() {
        assertWidths(listOf(100f, 100f, 100f), tabWidths(listOf(60, 100, 40), 300f, equal = true))
    }

    @Test
    fun eachToItsLabelWithTheRestSharedOut() {
        // 400 of labels in 500: 25 more each.
        assertWidths(listOf(225f, 85f, 125f, 65f), tabWidths(listOf(200, 60, 100, 40), 500f, equal = false))
    }

    @Test
    fun tooLittleRoomCutsEachBySameShare() {
        assertWidths(listOf(100f, 50f), tabWidths(listOf(200, 100), 150f, equal = false))
    }

    @Test
    fun theWidthsAddUpToTheRow() {
        assertEquals(1000f, tabWidths(listOf(180, 90, 130, 110, 120), 1000f, equal = false).sum(), 0.01f)
    }

    @Test
    fun thePillGlidesBetweenUnevenTabs() {
        val cells = Cells(floatArrayOf(0f, 200f, 260f), floatArrayOf(200f, 60f, 140f))
        assertEquals(0f, cells.leftAt(0f), 0.001f)
        assertEquals(100f, cells.leftAt(0.5f), 0.001f)
        assertEquals(130f, cells.widthAt(0.5f), 0.001f)
        assertEquals(260f, cells.leftAt(2f), 0.001f)
        assertEquals("past the end stays on the last", 260f, cells.leftAt(5f), 0.001f)
    }

    @Test
    fun noTabsNoWidths() {
        assertEquals(0, tabWidths(emptyList(), 300f, equal = false).size.toLong())
    }
}
