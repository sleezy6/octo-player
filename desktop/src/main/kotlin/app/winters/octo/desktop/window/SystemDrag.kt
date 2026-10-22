package app.winters.octo.desktop.window

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Window

// On Windows, the system moves a window Octo draws its own frame for, as it
// moves every window with a title bar of its own. The app's own moving read
// the pointer's and the window's places from Java, which lays screens of
// different scales out in units that do not line up, so a window dragged
// across them jumped about. Told to start moving (the SC_MOVE system
// command with the title bar's hit code), Windows runs the move itself:
// across screens of other scales, snapping to edges, Aero Snap and all.
object SystemDrag {
    private val user32: User32? by lazy {
        when {
            !System.getProperty("os.name").orEmpty().startsWith("Windows") -> null
            // A way back to the app's own moving: -Docto.window.systemDrag=false,
            // or OCTO_SYSTEM_DRAG=false in the environment.
            System.getProperty("octo.window.systemDrag", System.getenv("OCTO_SYSTEM_DRAG") ?: "true").equals("false", ignoreCase = true) -> null
            else -> runCatching { Native.load("user32", User32::class.java) }.getOrNull()
        }
    }

    // Whether the system moves windows here.
    val available: Boolean get() = user32 != null

    // Starts the system moving `window` with the pointer, while the mouse
    // button is held. True when the system took it.
    //
    // Java takes hold of the mouse (SetCapture) for the surface the button
    // went down on, and Windows will not start a move while anything else
    // holds it. The hold belongs to Java's own window thread, so it cannot
    // be let go from here; instead that surface is handed a button-up first,
    // which makes Java let go as it does for any release, and then the move
    // command. Both are posted, so the window's own thread does them in that
    // order while this one goes on drawing. The real release, when it comes,
    // ends the move.
    fun start(window: Window): Boolean {
        val lib = user32 ?: return false
        val frame = Native.getWindowPointer(window) ?: return false
        val at = Point()
        if (!lib.GetCursorPos(at)) return false
        // The surface under the pointer: Java's, inside the frame.
        val under = lib.WindowFromPoint(at.x.toLong() and 0xFFFFFFFFL or (at.y.toLong() shl 32)) ?: frame
        val local = Point(at.x, at.y)
        lib.ScreenToClient(under, local)
        val place = (local.x.toLong() and 0xFFFFL) or ((local.y.toLong() and 0xFFFFL) shl 16)
        // WPARAM and LPARAM are pointer-sized; Pointer values of that width
        // stand for them.
        lib.PostMessageW(under, WM_LBUTTONUP, Pointer.createConstant(0L), Pointer.createConstant(place))
        return lib.PostMessageW(frame, WM_SYSCOMMAND, Pointer.createConstant(SC_MOVE_BY_CAPTION), Pointer.createConstant(0L)) != 0
    }

    // Whether the left mouse button is down now, for knowing a system move
    // is still going.
    fun mouseHeld(): Boolean {
        val lib = user32 ?: return false
        return (lib.GetAsyncKeyState(VK_LBUTTON).toInt() and 0x8000) != 0
    }

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun PostMessageW(window: Pointer, message: Int, w: Pointer?, l: Pointer?): Int
        fun GetAsyncKeyState(key: Int): Short
        fun GetCursorPos(point: Point): Boolean
        // POINT passed by value: two 32-bit ints packed into one 64-bit word.
        fun WindowFromPoint(point: Long): Pointer?
        fun ScreenToClient(window: Pointer, point: Point): Boolean
    }

    @Structure.FieldOrder("x", "y")
    class Point(@JvmField var x: Int = 0, @JvmField var y: Int = 0) : Structure()

    private const val WM_LBUTTONUP = 0x0202
    private const val WM_SYSCOMMAND = 0x0112
    // SC_MOVE (0xF010) with HTCAPTION (2): move as if the title bar were grabbed.
    private const val SC_MOVE_BY_CAPTION = 0xF012L
    private const val VK_LBUTTON = 0x01
}
