package cn.listenly.app.ui.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import cn.listenly.app.ui.theme.Listenly
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

/**
 * What the glass under the current subtree may sample. Inside the page this is the wallpaper
 * layer; for floating controls it is the whole recorded page. Null on Android < 13.
 */
val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * One tilt-following specular highlight for the whole app (one sensor listener, not one per
 * glass surface). Read `.value` only in draw lambdas.
 */
val LocalSpecular = staticCompositionLocalOf<State<Highlight>?> { null }

@Composable
fun rememberAppSpecular(): State<Highlight> = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)

/**
 * "Regular" glass for content cards: a real frosted pass over the wallpaper (blur, vibrancy,
 * a soft lens at the rim) plus the shared specular rim light. Falls back to a tinted surface
 * with a painted rim on Android < 13.
 */
@Composable
fun Modifier.regularGlass(shape: CornerBasedShape): Modifier {
    val colors = Listenly.colors
    val dark = colors.isDark
    val backdrop = LocalPageBackdrop.current
    val specular = LocalSpecular.current
    val shadow = Modifier.dropShadow(
        shape,
        Shadow(radius = 28.dp, color = Color.Black, alpha = if (dark) 0.32f else 0.09f, offset = DpOffset(0.dp, 10.dp)),
    )
    if (backdrop == null || !isRuntimeShaderSupported()) {
        return this.then(shadow).background(colors.glassRegular, shape).rim(shape, dark, strong = false)
    }
    return this.then(shadow).drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            padding = maxOf(padding, 24.dp.toPx())
            vibrancy()
            blur(18.dp.toPx(), 18.dp.toPx())
            lens(refractionHeight = 14.dp.toPx(), refractionAmount = 10.dp.toPx())
        },
        highlight = specular?.let { s -> { s.value.copy(alpha = if (dark) 0.35f else 0.55f) } },
        onDrawSurface = { drawRect(colors.glassRegular) },
        onDrawFront = { rimLight(shape, dark, strong = false) },
    )
}

/**
 * "Clear" Liquid Glass for floating controls: light blur, vibrancy, rim refraction (lens) and the
 * tilt-following specular. [press] (0..1) adds depth, chromatic dispersion and an inner shade,
 * like the miuix navigation indicator.
 */
@Composable
fun Modifier.liquidGlass(
    shape: CornerBasedShape,
    press: () -> Float = { 0f },
    tint: Color = Listenly.colors.glassClear,
    refraction: Dp = 16.dp,
): Modifier {
    val backdrop = LocalPageBackdrop.current
    val dark = Listenly.colors.isDark
    val specular = LocalSpecular.current
    val shadow = Modifier.dropShadow(shape, Shadow(radius = 16.dp, color = Color.Black, alpha = if (dark) 0.38f else 0.13f, offset = DpOffset(0.dp, 4.dp)))
    if (backdrop == null || !isRuntimeShaderSupported()) {
        val fill = tint.copy(alpha = (tint.alpha * 2.2f).coerceAtMost(if (dark) 0.6f else 0.85f))
        return this.then(shadow).background(fill, shape).rim(shape, dark, strong = true)
    }
    return this.then(shadow).drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            val p = press()
            padding = maxOf(padding, (refraction + 14.dp).toPx())
            vibrancy()
            blur(3.dp.toPx(), 3.dp.toPx())
            lens(
                refractionHeight = refraction.toPx(),
                refractionAmount = (refraction + 10.dp * p).toPx(),
                depthEffect = p > 0f,
                chromaticAberration = 0.5f * p,
            )
        },
        highlight = specular?.let { s -> { s.value.copy(alpha = 0.75f + 0.25f * press()) } },
        onDrawSurface = {
            drawRect(tint)
            val p = press()
            if (p > 0f) drawRect(Color.Black.copy(alpha = 0.05f * p))
        },
        onDrawFront = { rimLight(shape, dark, strong = true) },
    )
}

/** A thin bright rim: strongest at the top-left like light from above, fading round the edge. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.rimLight(shape: CornerBasedShape, dark: Boolean, strong: Boolean) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val top = if (dark) (if (strong) 0.32f else 0.18f) else (if (strong) 0.95f else 0.85f)
    drawOutline(
        outline,
        Brush.linearGradient(
            0f to Color.White.copy(alpha = top),
            0.45f to Color.White.copy(alpha = top * 0.25f),
            0.8f to Color.White.copy(alpha = top * 0.12f),
            1f to Color.White.copy(alpha = top * 0.45f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        style = Stroke(width = (if (strong) 1.2f else 1f).dp.toPx()),
    )
}

private fun Modifier.rim(shape: CornerBasedShape, dark: Boolean, strong: Boolean) = drawWithContent {
    drawContent()
    rimLight(shape, dark, strong)
}

/** Springy 0→1 press progress; read it lazily (inside draw/layer lambdas) to avoid recomposition. */
@Composable
fun rememberPressProgress(interaction: MutableInteractionSource): State<Float> {
    val isPressed by interaction.collectIsPressedAsState()
    return animateFloatAsState(
        if (isPressed) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
}

/** A round glass icon button (44 dp touch target), e.g. back, theme, import. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Listenly.colors.text,
    size: Dp = 44.dp,
) {
    GlassBox(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier.size(size).semantics { this.contentDescription = contentDescription },
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/** Generic clickable Liquid Glass container with press feedback (grows and refracts harder). */
@Composable
fun GlassBox(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = ContinuousShape(22.dp),
    role: Role = Role.Button,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressState = rememberPressProgress(interaction)
    val press = { pressState.value }
    Box(
        modifier = modifier
            .graphicsLayer {
                val s = 1f + 0.08f * press()
                scaleX = s
                scaleY = s
            }
            .liquidGlass(shape, press)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, role = role, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}
