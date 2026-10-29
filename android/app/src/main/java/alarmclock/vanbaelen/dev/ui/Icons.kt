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

    // Night display and wake-up screen (docs/design.md, Night display).
    val sunrise = stroke("M4 18h16M7 18a5 5 0 0 1 10 0M12 8V6M6.5 11.5 5 10M17.5 11.5 19 10")
    val bell = stroke("M6 16v-5a6 6 0 1 1 12 0v5l1.5 2h-15L6 16zM10 21a2 2 0 0 0 4 0")
    val bolt = stroke("M13 3L5 14h6l-1 7 8-11h-6l1-7z")
    val moon = stroke("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val eye = stroke("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", 1.6f)
    val eyeClosed = stroke("M2 12s3.5 5 10 5 10-5 10-5M5 15l-1.5 2M19 15l1.5 2M12 17v2.5M8.5 16.5 7.5 19M15.5 16.5l1 2.5", 1.6f)
    val home = stroke("M4 11l8-7 8 7v9H4zM10 20v-5h4v5", 1.6f)
    val calendar = stroke("M5 5h14v15H5zM5 9h14M9 3v4M15 3v4", 1.6f)
    val battery = stroke("M3 8h15v8H3zM21 11v2", 1.6f)
    val cloud = stroke("M7 18a4 4 0 0 1-.6-7.96A5.5 5.5 0 0 1 17 10.5a3.75 3.75 0 0 1 0 7.5H7z", 1.5f)
    val rain = stroke("M7 15a4 4 0 0 1-.6-7.96A5.5 5.5 0 0 1 17 8.5a3.5 3.5 0 0 1 0 6.5H7zM9 18l-1 2M13 18l-1 2M17 18l-1 2", 1.5f)
    val sun = stroke("M12 4v2M12 18v2M4 12h2M18 12h2M6.3 6.3l1.4 1.4M16.3 16.3l1.4 1.4M6.3 17.7l1.4-1.4M16.3 7.7l1.4-1.4M15.5 12a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0z", 1.5f)
    val snow = stroke("M7 15a4 4 0 0 1-.6-7.96A5.5 5.5 0 0 1 17 8.5a3.5 3.5 0 0 1 0 6.5H7zM9 19h.01M13 19h.01M17 19h.01M11 21h.01M15 21h.01", 1.5f)
    val storm = stroke("M7 15a4 4 0 0 1-.6-7.96A5.5 5.5 0 0 1 17 8.5a3.5 3.5 0 0 1 0 6.5H7zM12 15l-2 4h4l-2 4", 1.5f)
    val fog = stroke("M4 10h16M6 14h12M8 18h8M7 6h10", 1.5f)
    val wind = stroke("M3 9h11a3 3 0 1 0-3-3M3 15h15a3 3 0 1 1-3 3M3 12h8", 1.5f)

    /** Icon of a Home Assistant weather condition. */
    fun weather(condition: String?): ImageVector = when (condition) {
        "sunny" -> sun
        "clear-night" -> moon
        "rainy", "pouring" -> rain
        "snowy", "snowy-rainy", "hail" -> snow
        "lightning", "lightning-rainy" -> storm
        "fog" -> fog
        "windy", "windy-variant" -> wind
        else -> cloud
    }

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
