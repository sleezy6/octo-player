package app.winters.octo.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Indication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

// The darker pill's shade.
private val PillFill = Color.Black.copy(alpha = 0.72f)

// How strongly a tab the selection passes over flashes.
const val GearShiftTint = 0.22f

// How strongly a tab at `index` flashes while the selection pill, at
// `pill` (in tabs from the first), passes over it: most as the pill's
// middle crosses it, none once the pill is a whole tab away. The tab the
// pill is heading for does not flash; it is where the pill lands.
fun gearShiftTint(index: Int, pill: Float, selected: Int): Float {
    if (index == selected) return 0f
    return GearShiftTint * (1f - abs(pill - index)).coerceIn(0f, 1f)
}

// A few choices in one glaze tray, the chosen one in the darker pill that
// glides between them. The tray is recessed, with a hairline of accent at
// its edge; the pill is lit faintly in the accent from the side it came
// from, and the tabs it passes on its way flash a little accent, like
// gears going by. With `fillWidth` the tabs share the width given;
// without, each is as wide as the widest label, or, when they would not
// all fit so, as wide as its own label with the room left shared out, so
// one long label is not cut short beside short ones.
@Composable
fun GlazeTabs(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    fillWidth: Boolean = true,
    // What a tab shows while it has the keyboard; none by default.
    indication: Indication? = null,
    // Whether a tab can be picked; a tab that cannot is shown but ignores
    // presses.
    enabled: (Int) -> Boolean = { true },
    tab: @Composable (index: Int, chosen: Boolean) -> Unit,
) {
    val motion = motionScale()
    val pill = remember { Animatable(selected.toFloat()) }
    // Which way it last went: 1 to the end, -1 to the start.
    var way by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(selected) {
        if (pill.value != selected.toFloat()) way = if (selected > pill.value) 1f else -1f
        if (motion.still) pill.snapTo(selected.toFloat()) else pill.animateTo(selected.toFloat(), octoTween(motion, OctoDuration.Neutral, OctoEasing.Smooth))
    }
    // Where each tab starts and how wide it is, from the last layout.
    var cells by remember { mutableStateOf(Cells.None) }

    Glaze(
        modifier
            .height(height)
            .dropShadow(CircleShape, Shadow(radius = shadowBlur(10f), color = Color.Black.copy(alpha = 0.20f), offset = DpOffset(0.dp, 2.dp))),
    ) {
        // Recessed: a shade falling from the top inside edge, and a hairline
        // of accent round the rim.
        Box(
            Modifier
                .matchParentSize()
                .innerShadow(CircleShape, Shadow(radius = shadowBlur(2f), spread = 1.6.dp, color = Color.Black.copy(alpha = 0.15f), offset = DpOffset(0.dp, 3.dp)))
                .innerShadow(CircleShape, Shadow(radius = 0.dp, spread = 0.5.dp, color = OctoColors.Accent.copy(alpha = 0.25f))),
        )
        Layout(
            content = {
                repeat(count) { index ->
                    val chosen = index == selected
                    Box(
                        Modifier.selectable(
                            selected = chosen,
                            enabled = enabled(index),
                            interactionSource = null,
                            indication = indication,
                            role = Role.Tab,
                        ) { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Sharing a width, the words may use nearly all of
                        // their tab; hugging them, the tabs keep some air.
                        Box(Modifier.padding(horizontal = if (fillWidth) 4.dp else 14.dp)) { tab(index, chosen) }
                    }
                }
            },
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .fillMaxHeight()
                .padding(4.dp)
                .selectableGroup()
                .drawBehind {
                    if (cells.count == 0) return@drawBehind
                    val at = pill.value
                    val corner = CornerRadius(size.height / 2f)
                    val left = cells.leftAt(at)
                    val cell = cells.widthAt(at)
                    drawRoundRect(PillFill, Offset(left, 0f), Size(cell, size.height), corner)
                    // The accent light on the pill, from the side it came
                    // from, stronger while it moves.
                    val moving = (abs(at - selected) * 2f).coerceIn(0f, 1f)
                    val lit = OctoColors.Accent.copy(alpha = 0.10f + 0.12f * moving)
                    val from = if (way > 0f) left else left + cell
                    val to = if (way > 0f) left + cell * 0.7f else left + cell * 0.3f
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(lit, Color.Transparent), startX = from, endX = to),
                        Offset(left, 0f),
                        Size(cell, size.height),
                        corner,
                    )
                    // Gears going by.
                    for (index in 0 until count) {
                        val tint = if (pill.isRunning) gearShiftTint(index, at, selected) else 0f
                        if (tint > 0f) {
                            drawRoundRect(OctoColors.Accent.copy(alpha = tint), Offset(cells.lefts[index], 0f), Size(cells.widths[index], size.height), corner)
                        }
                    }
                },
        ) { measurables, constraints ->
            val tall = constraints.maxHeight
            val natural = measurables.map { it.maxIntrinsicWidth(tall) }
            val widest = natural.maxOrNull() ?: 0
            val width = if (fillWidth && constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                (widest * count).coerceIn(constraints.minWidth, if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE)
            }
            val widths = tabWidths(natural, width.toFloat(), equal = fillWidth || widest * count <= width)
            val lefts = FloatArray(count).also { for (i in 1 until count) it[i] = it[i - 1] + widths[i - 1] }
            val next = Cells(lefts, widths)
            if (next != cells) cells = next
            val placeables = measurables.mapIndexed { index, it ->
                val right = (lefts[index] + widths[index]).roundToInt()
                it.measure(Constraints.fixed(right - lefts[index].roundToInt(), tall))
            }
            layout(width, tall) {
                placeables.forEachIndexed { index, placeable -> placeable.place(lefts[index].roundToInt(), 0) }
            }
        }
    }
}

// How wide each tab is, `width` in all: the same for every tab when
// `equal`; otherwise each as wide as its own label (`natural`), with the
// room left shared out evenly, or, with too little room for every label,
// each cut by the same share.
internal fun tabWidths(natural: List<Int>, width: Float, equal: Boolean): FloatArray {
    val count = natural.size
    if (count == 0) return FloatArray(0)
    if (equal) return FloatArray(count) { width / count }
    val total = natural.sum().toFloat()
    if (total <= 0f) return FloatArray(count) { width / count }
    if (total <= width) {
        val spare = (width - total) / count
        return FloatArray(count) { natural[it] + spare }
    }
    return FloatArray(count) { natural[it] * width / total }
}

// The tabs as laid out: where each starts and how wide it is. The pill,
// part way between two tabs, takes the place and width between theirs.
internal class Cells(val lefts: FloatArray, val widths: FloatArray) {
    val count: Int get() = lefts.size

    fun leftAt(at: Float): Float = between(lefts, at)

    fun widthAt(at: Float): Float = between(widths, at)

    private fun between(values: FloatArray, at: Float): Float {
        val clamped = at.coerceIn(0f, (count - 1).toFloat())
        val from = clamped.toInt()
        val to = minOf(from + 1, count - 1)
        val part = clamped - from
        return values[from] + (values[to] - values[from]) * part
    }

    override fun equals(other: Any?): Boolean = other is Cells && lefts.contentEquals(other.lefts) && widths.contentEquals(other.widths)

    override fun hashCode(): Int = 31 * lefts.contentHashCode() + widths.contentHashCode()

    companion object {
        val None = Cells(FloatArray(0), FloatArray(0))
    }
}
