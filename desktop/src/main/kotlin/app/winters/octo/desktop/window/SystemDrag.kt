package app.winters.octo.desktop.window

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
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
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    // Whether the system moves windows here.
    val available: Boolean get() = user32 != null

    // Starts the system moving `window` with the pointer, while the mouse
    // button is held. True when the system took it.
    fun start(window: Window): Boolean {
        val lib = user32 ?: return false
        val handle = Native.getWindowPointer(window) ?: return false
        // Posted, not sent: the window's own thread runs the move, and this
        // one goes on drawing.
        // WPARAM and LPARAM are pointer-sized, which NativeLong is not on
        // 64-bit Windows; Pointer values of the same width stand for them.
        return lib.PostMessageW(handle, WM_SYSCOMMAND, Pointer.createConstant(SC_MOVE_BY_CAPTION), Pointer.createConstant(0L)) != 0
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
    }

    private const val WM_SYSCOMMAND = 0x0112
    // SC_MOVE (0xF010) with HTCAPTION (2): move as if the title bar were grabbed.
    private const val SC_MOVE_BY_CAPTION = 0xF012L
    private const val VK_LBUTTON = 0x01
}
