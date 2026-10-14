package app.winters.octo.desktop.pages

import androidx.compose.runtime.Composable
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.MotionCoverPrefs
import app.winters.octo.desktop.settings.MotionGrids

// Animated covers: on or off, and how album grids show them. The signed-in
// Octo server finds them; one that does not offer them says so here.
@Composable
internal fun MotionCoverGroup(app: AppState, settings: AppSettings) {
    val prefs = settings.appearance.motionCovers
    fun motion(edit: (MotionCoverPrefs) -> MotionCoverPrefs) =
        app.settings.update { it.copy(appearance = it.appearance.copy(motionCovers = edit(it.appearance.motionCovers))) }
    val offered = app.motionCovers.art.offered()
    Group("Animated covers") {
        val caption = when {
            !offered -> "Your server doesn't offer them. An Octo server with an animated artwork server set does."
            LocalReduceMotion.current -> "Hold still while motion is reduced, by Calm motion or your system."
            else -> "Apple Music's moving covers, in the full player, the mini player, on album pages and in album grids."
        }
        SwitchRow("Animated covers", caption, prefs.enabled) { on -> motion { it.copy(enabled = on) } }
        if (prefs.enabled && offered) {
            ChoiceRow("In album grids", gridsHelp(prefs.grids), MotionGrids.entries, prefs.grids, ::gridsName) { grids ->
                motion { it.copy(grids = grids) }
            }
        }
    }
}

private fun gridsName(grids: MotionGrids): String = when (grids) {
    MotionGrids.Off -> "Off"
    MotionGrids.Hover -> "On hover"
    MotionGrids.Always -> "Always"
}

private fun gridsHelp(grids: MotionGrids): String = when (grids) {
    MotionGrids.Off -> "Album grids keep still covers."
    MotionGrids.Hover -> "The album under the pointer moves."
    MotionGrids.Always -> "Every album in sight moves. Uses more power and downloads more."
}
