package app.winters.octo.desktop.window

import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Timer

// Keeps a window's size steady when it moves onto a screen of another
// scale. Windows keeps a window's size in scaled units across screens, so
// one moved from a 100% screen to a 150% one comes out half as big again on
// it, often bigger than that screen, and some moves grow it more. Once the
// window settles on its new screen it goes back to the size it had, made
// no bigger than that screen's usable part, and kept on it. Nothing
// happens while it fills the screen (`filled`), which has its own fit.
class ScreenChange(
    private val window: Window,
    private val filled: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // The size the window had, while it stayed on one screen: the latest,
    // and the one before it. The system's own resize for the new screen
    // can come just before the window says it changed screens, so a size
    // only moments old is not trusted then.
    private val sizes = SteadySize(window.size)
    private var screen: GraphicsConfiguration? = window.graphicsConfiguration
    private var pending = false
    private var target: Dimension = window.size

    // A move across goes on for a while; the size is set once it stops.
    private val settle = Timer(SETTLE_MS) { settled() }.apply { isRepeats = false }

    init {
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                // A resize on the same screen is the listener's own.
                if (!pending && window.graphicsConfiguration == screen) sizes.saw(window.size, clock())
                if (pending) settle.restart()
            }

            override fun componentMoved(e: ComponentEvent?) {
                if (pending) settle.restart()
            }
        })
        window.addPropertyChangeListener("graphicsConfiguration") {
            if (window.graphicsConfiguration == screen) return@addPropertyChangeListener
            if (!pending) target = sizes.before(clock())
            pending = true
            settle.restart()
        }
    }

    private fun settled() {
        pending = false
        screen = window.graphicsConfiguration
        if (!filled() && window.isShowing) {
            val fitted = fitOnScreen(window.bounds, target, usableArea(window), window.minimumSize)
            if (fitted != window.bounds) window.bounds = fitted
        }
        sizes.reset(window.size)
    }

    private companion object {
        const val SETTLE_MS = 300
    }
}

// A window's latest size and the one it had before it, for telling the
// listener's own size from one the system set as the window changed
// screens: a size newer than `freshMs` gives way to the one before.
class SteadySize(start: Dimension, private val freshMs: Long = 600) {
    private var latest = start
    // When the latest size came; null for one the window started with.
    private var latestAt: Long? = null
    private var previous = start

    private fun fresh(at: Long): Boolean = latestAt?.let { at - it < freshMs } ?: false

    fun saw(size: Dimension, at: Long) {
        if (size == latest) return
        // A run of quick resizes (dragging an edge) keeps the size from
        // before the run as the one before.
        if (!fresh(at)) previous = latest
        latest = Dimension(size)
        latestAt = at
    }

    // The size to keep for a screen change now.
    fun before(at: Long): Dimension = Dimension(if (fresh(at)) previous else latest)

    fun reset(size: Dimension) {
        latest = Dimension(size)
        previous = latest
        latestAt = null
    }
}

// The bounds a window takes on a new screen: the size it had before
// (`size`), no bigger than the screen's usable part (`area`) and no smaller
// than its least (`least`, which wins on a screen too small for it), placed
// where it is now but moved in to stay on that screen.
fun fitOnScreen(now: Rectangle, size: Dimension, area: Rectangle, least: Dimension): Rectangle {
    val w = maxOf(least.width, minOf(size.width, area.width))
    val h = maxOf(least.height, minOf(size.height, area.height))
    val x = now.x.coerceIn(area.x, maxOf(area.x, area.x + area.width - w))
    val y = now.y.coerceIn(area.y, maxOf(area.y, area.y + area.height - h))
    return Rectangle(x, y, w, h)
}

// The usable part of the screen a window is mostly on: without the taskbar
// or panels, in the window's own units.
internal fun usableArea(window: Window): Rectangle {
    val config = window.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    return Rectangle(bounds.x + insets.left, bounds.y + insets.top, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
}
