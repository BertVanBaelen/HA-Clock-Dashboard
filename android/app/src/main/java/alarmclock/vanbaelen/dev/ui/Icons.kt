package alarmclock.vanbaelen.dev.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons of the mockup (24×24, stroke 1.75); tinted by Icon like any ImageVector. */
object AppIcons {
    val plus = stroke("M12 5v14M5 12h14")
    val minus = stroke("M5 12h14")
    val back = stroke("M15 6l-6 6 6 6")
    val up = stroke("M6 15l6-6 6 6")
    val down = stroke("M6 9l6 6 6-6")
    val upDown = stroke("M8 9l4-4 4 4M8 15l4 4 4-4", 2f)
    val sound = stroke("M4 9v6h4l5 4V5L8 9H4zM16.5 8.5a5 5 0 0 1 0 7")
    val vibrate = stroke("M9 4h6a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM4 9v6M20 9v6")
    val vibrateOff = stroke("M9 4h6a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM3 3l18 18")
    val repeat = stroke("M17 3l3 3-3 3M4 11V9a3 3 0 0 1 3-3h13M7 21l-3-3 3-3M20 13v2a3 3 0 0 1-3 3H4")
    val snooze = stroke("M12 21a8 8 0 1 0 0-16a8 8 0 1 0 0 16zM12 9v4l2.5 2M5 3L2 6M19 3l3 3")
    val sync = stroke("M20 11a8 8 0 0 0-14.9-3M4 4v4h4M4 13a8 8 0 0 0 14.9 3M20 20v-4h-4")
    val more = stroke("M5 12h.01M12 12h.01M19 12h.01", 3f)
    val warning = stroke("M12 4l9 16H3zM12 10v4M12 17h.01")
    val script = stroke("M7 4h10a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM9 9h6M9 13h6M9 17h3")

    private fun stroke(path: String, width: Float = 1.75f): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    private val cache = HashMap<String, ImageVector?>()

    /** A script's MDI icon (24×24 fill path from the server), or null when it cannot be parsed. */
    fun mdi(path: String?): ImageVector? {
        if (path.isNullOrBlank()) return null
        return synchronized(cache) {
            cache.getOrPut(path) {
                runCatching {
                    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
                        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
                        .build()
                }.getOrNull()
            }
        }
    }
}
