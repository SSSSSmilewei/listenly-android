package cn.listenly.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Stroke icons drawn from the same 24×24 paths as the Claude Design mockups. */
object AppIcons {
    private fun stroke(name: String, vararg paths: String, width: Float = 1.9f) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun fill(name: String, vararg paths: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
        }.build()

    val Headphones = stroke(
        "headphones",
        "M4 14v-3a8 8 0 0 1 16 0v3",
        "M5 12h0a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h0a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z",
        "M19 12h0a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h0a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z",
    )
    val Book = stroke("book", "M12 5c-3-2-7-2-9-1v15c3-1 6-1 9 1 3-2 6-2 9-1V4c-2-1-6-1-9 1zM12 5v15")
    val User = stroke("user", "M16 8a4 4 0 1 1-8 0 4 4 0 0 1 8 0z", "M4 21v-1a8 8 0 0 1 16 0v1")
    val Plus = stroke("plus", "M12 5v14M5 12h14")
    val Moon = stroke("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z", width = 1.8f)
    val Sun = stroke(
        "sun",
        "M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0z",
        "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
        width = 1.8f,
    )
    val Back = stroke("back", "m14 5-7 7 7 7", width = 2f)
    val Close = stroke("close", "m6 6 12 12M18 6 6 18", width = 2f)
    val ChevronDown = stroke("chevronDown", "m6 9 6 6 6-6", width = 2f)
    val ChevronUp = stroke("chevronUp", "m6 15 6-6 6 6", width = 2f)
    val ChevronRight = stroke("chevronRight", "m9 5 7 7-7 7", width = 2f)
    val Check = stroke("check", "m5 12 4 4 10-10", width = 2.6f)
    val Rewind5 = stroke("rewind", "M4 12a8 8 0 1 0 2.3-5.6M4 4v4h4", width = 1.8f)
    val Forward5 = stroke("forward", "M20 12a8 8 0 1 1-2.3-5.6M20 4v4h-4", width = 1.8f)
    val PrevSentence = stroke("prev", "M6 5v14M18 6l-9 6 9 6z", width = 1.8f)
    val NextSentence = stroke("next", "M18 5v14M6 6l9 6-9 6z", width = 1.8f)
    val Repeat = stroke("repeat", "m17 2 4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3", width = 1.8f)
    val RepeatOne = stroke("repeatOne", "m17 2 4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3M11 10h1v5", width = 1.8f)
    val Sequential = stroke("sequential", "M4 7h13l-3-3M20 17H7l3 3")
    val Shuffle = stroke("shuffle", "M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5")
    val Search = stroke("search", "M18 11a7 7 0 1 1-14 0 7 7 0 0 1 14 0zM20 20l-3.5-3.5", width = 2f)
    val Sync = stroke("sync", "M20 7a8.5 8.5 0 0 0-15 2M4 17a8.5 8.5 0 0 0 15-2M20 3v4h-4M4 21v-4h4", width = 1.8f)
    val Bookmark = stroke("bookmark", "M6 3h12v18l-6-4-6 4z", width = 1.8f)
    val BookmarkFilled = fill("bookmarkFilled", "M6 3h12v18l-6-4-6 4z")
    val Play = fill("play", "M8.1 4.8C7.2 4.2 6 4.9 6 6v12c0 1.1 1.2 1.8 2.1 1.2l10-6c1-.6 1-1.8 0-2.4l-10-6Z")
    val Pause = fill(
        "pause",
        "M7.4 4.5h1.6a1.4 1.4 0 0 1 1.4 1.4v12.2a1.4 1.4 0 0 1-1.4 1.4H7.4A1.4 1.4 0 0 1 6 18.1V5.9a1.4 1.4 0 0 1 1.4-1.4z",
        "M15 4.5h1.6A1.4 1.4 0 0 1 18 5.9v12.2a1.4 1.4 0 0 1-1.4 1.4H15a1.4 1.4 0 0 1-1.4-1.4V5.9A1.4 1.4 0 0 1 15 4.5z",
    )
    val Wrong = stroke("wrong", "m7 7 10 10M17 7 7 17", width = 2.6f)
}
