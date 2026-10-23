package cn.listenly.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The original day/night wallpaper carried over from 2.x (wallpapers/day.svg, night.svg):
 * a soft diagonal gradient plus one warm glow. Glass needs something behind it to refract.
 * Colors cross-fade natively when the theme flips.
 */
@Composable
fun Wallpaper(dark: Boolean, modifier: Modifier = Modifier) {
    val spec = tween<Color>(durationMillis = 480)
    val c0 by animateColorAsState(if (dark) Color(0xFF0D1629) else Color(0xFFCDDDE5), spec, label = "wp0")
    val c1 by animateColorAsState(if (dark) Color(0xFF142733) else Color(0xFFB9CFCF), spec, label = "wp1")
    val c2 by animateColorAsState(if (dark) Color(0xFF152C2A) else Color(0xFFD6D3C2), spec, label = "wp2")
    val c3 by animateColorAsState(if (dark) Color(0xFF101914) else Color(0xFFBBC7B7), spec, label = "wp3")
    val glow by animateColorAsState(if (dark) Color(0x736B5141) else Color(0xD9F4EADB), spec, label = "glow")
    Canvas(modifier.fillMaxSize()) {
        drawRect(
            Brush.linearGradient(
                0f to c0, 0.38f to c1, 0.68f to c2, 1f to c3,
                start = Offset.Zero,
                end = Offset(size.width * 0.7f, size.height),
            ),
        )
        val radius = size.maxDimension * 0.62f
        drawCircle(
            Brush.radialGradient(
                listOf(glow, glow.copy(alpha = 0f)),
                center = Offset(size.width * 0.22f, size.height * (if (dark) 0.52f else 0.38f)),
                radius = radius,
            ),
            radius = radius,
            center = Offset(size.width * 0.22f, size.height * (if (dark) 0.52f else 0.38f)),
        )
    }
}
