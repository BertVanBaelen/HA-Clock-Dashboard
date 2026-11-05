package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.Routes
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

/** The landscape layouts apply when the window is wider than it is high (docs/design.md, Landscape). */
@Composable
fun isLandscape(): Boolean {
    val c = LocalConfiguration.current
    return c.screenWidthDp > c.screenHeightDp
}

/** The landscape layouts' left rail, in place of the bottom bar: Alarms · Night · Settings. [current] is [Routes.LIST] or [Routes.SETTINGS]. */
@Composable
fun LandscapeRail(current: String, nav: NavController, modifier: Modifier = Modifier) {
    val t = tokens
    Column(
        modifier
            .fillMaxHeight()
            .drawBehind { drawLine(t.line, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .width(64.dp)
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RailItem(stringResource(R.string.nav_alarms), AppIcons.bell, current == Routes.LIST) {
            nav.navigate(Routes.LIST) { popUpTo(Routes.LIST); launchSingleTop = true }
        }
        RailItem(stringResource(R.string.nav_night), AppIcons.moon, false) { nav.navigate(Routes.NIGHT) }
        RailItem(stringResource(R.string.settings), AppIcons.sliders, current == Routes.SETTINGS) {
            nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
        }
    }
}

@Composable
private fun RailItem(label: String, icon: ImageVector, current: Boolean, onClick: () -> Unit) {
    val t = tokens
    Column(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (current) t.soft else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = current },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = if (current) t.ink else t.muted, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (current) t.ink else t.muted, maxLines = 1)
    }
}
