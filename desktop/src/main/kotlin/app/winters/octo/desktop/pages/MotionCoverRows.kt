package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlassField
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.motion.ServerCheck
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.MotionCoverPrefs
import kotlinx.coroutines.delay

// How long typing rests before the address is kept and checked, so a half
// typed one is never asked.
private const val SETTLE_MS = 800L

// Animated covers: on or off, and the artwork server that finds them. The
// address is kept once typing rests, and checked then, in a line under it.
@Composable
internal fun MotionCoverGroup(app: AppState, settings: AppSettings) {
    val prefs = settings.appearance.motionCovers
    fun motion(edit: (MotionCoverPrefs) -> MotionCoverPrefs) =
        app.settings.update { it.copy(appearance = it.appearance.copy(motionCovers = edit(it.appearance.motionCovers))) }
    Group("Animated covers") {
        val calm = LocalReduceMotion.current
        SwitchRow(
            "Animated covers",
            if (calm) "Hold still while motion is reduced, by Calm motion or your system."
            else "Apple Music's moving covers, in the full player, the mini player and on album pages.",
            prefs.enabled,
        ) { on -> motion { it.copy(enabled = on) } }
        if (prefs.enabled) {
            var typed by remember { mutableStateOf(prefs.server) }
            var check by remember { mutableStateOf<ServerCheck?>(null) }
            LaunchedEffect(typed) {
                if (typed.isBlank()) {
                    check = null
                    if (prefs.server.isNotEmpty()) motion { it.copy(server = "") }
                    return@LaunchedEffect
                }
                delay(SETTLE_MS)
                if (typed.trim() != prefs.server) motion { it.copy(server = typed.trim()) }
                check = app.motionCovers.art.check(typed)
            }
            val caption = check?.words ?: if (typed.isBlank()) {
                "Your apple-music-animated-artworks server. Only an album's artist and name are sent to it."
            } else {
                "Checking…"
            }
            SettingRow("Artwork server", caption) {
                GlassField(
                    typed,
                    { typed = it },
                    Modifier.width(ServerFieldWidth),
                    placeholder = "artwork.example.com",
                )
            }
        }
    }
}

private val ServerFieldWidth = 260.dp
