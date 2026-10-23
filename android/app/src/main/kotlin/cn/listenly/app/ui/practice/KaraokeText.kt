package cn.listenly.app.ui.practice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import top.yukonga.miuix.kmp.basic.Text

/**
 * The current sentence, filled with [highlight] left-to-right, line by line, as the audio plays
 * (the "lyrics" idea from SPlayer-Next). [progress] is read once per frame in the draw phase,
 * so the highlight moves at display refresh rate without recomposing anything.
 */
@Composable
fun KaraokeText(
    text: String,
    progress: () -> Float,
    playing: Boolean,
    color: Color,
    highlight: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.SemiBold,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fraction by remember { mutableFloatStateOf(progress()) }
    val latest by rememberUpdatedState(progress)
    LaunchedEffect(playing) {
        fraction = latest()
        while (playing) withFrameNanos { fraction = latest() }
    }
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val result = layout ?: return@drawWithContent
            val chars = (fraction.coerceIn(0f, 1f) * text.length).toInt()
            if (chars <= 0) return@drawWithContent
            val path = Path()
            val lastLine = result.getLineForOffset((chars - 1).coerceAtLeast(0))
            for (line in 0..lastLine) {
                val right = if (line < lastLine) result.getLineRight(line) else result.getHorizontalPosition(chars, usePrimaryDirection = true)
                path.addRect(
                    androidx.compose.ui.geometry.Rect(result.getLineLeft(line), result.getLineTop(line), right, result.getLineBottom(line)),
                )
            }
            clipPath(path) {
                drawText(result, color = highlight)
            }
        },
    )
}
