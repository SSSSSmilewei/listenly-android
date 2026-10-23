package cn.listenly.app.ui.glass

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect

/**
 * HyperOS / iOS style continuous-curvature corners (a squircle), not circular arcs.
 *
 * It stays a [CornerBasedShape] on purpose: the Liquid Glass `lens` shader reads the corner
 * radii from it, while everything that draws or clips gets the smooth squircle outline.
 * A radius of half the height yields a continuous capsule.
 */
class ContinuousShape(private val corner: CornerSize) : CornerBasedShape(corner, corner, corner, corner) {

    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize) =
        ContinuousShape(topStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline = Outline.Generic(
        Path().apply { addSquircleRect(size.width, size.height, topStart, SquircleDefaults.Extension) },
    )

    override fun equals(other: Any?) = other is ContinuousShape && other.corner == corner
    override fun hashCode() = corner.hashCode()
    override fun toString() = "ContinuousShape($corner)"
}

fun ContinuousShape(radius: Dp) = ContinuousShape(CornerSize(radius))

/** A capsule with continuous ends. */
val ContinuousCapsule = ContinuousShape(CornerSize(50))
