package cn.listenly.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import cn.listenly.app.ui.glass.lens
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.drawBackdrop
import cn.listenly.app.ui.glass.LocalSpecular
import cn.listenly.app.ui.glass.LocalPageBackdrop
import cn.listenly.app.ui.glass.ContinuousCapsule
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.draw.dropShadow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.listenly.app.ui.glass.ContinuousShape
import cn.listenly.app.ui.glass.GlassBox
import cn.listenly.app.ui.glass.liquidGlass
import cn.listenly.app.ui.glass.regularGlass
import cn.listenly.app.ui.glass.rememberPressProgress
import cn.listenly.app.ui.theme.Listenly
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

fun formatTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/** Large MIUI-style page title with trailing glass actions. */
@Composable
fun LargeHeader(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 26.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = Listenly.colors.text, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Listenly.colors.textSecondary,
        modifier = modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
    )
}

/** Regular-glass content card. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Listenly.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .regularGlass(ContinuousShape(radius))
            .padding(padding),
        content = content,
    )
}

/** The accent play/pause button with an inner glass sheen. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val colors = Listenly.colors
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction)
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val s = 1f - 0.06f * press.value
                scaleX = s
                scaleY = s
            }
            .background(colors.accent, CircleShape)
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.22f), radius = this.size.minDimension / 2 - 1.dp.toPx(), center = center.copy(y = center.y - 1.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (playing) "暂停" else "播放" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) AppIcons.Pause else AppIcons.Play, null, tint = colors.onAccent, modifier = Modifier.size(size * 0.4f))
    }
}

/** Plain icon button (44 dp target) without a glass body. */
@Composable
fun BareIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Listenly.colors.text, iconSize: Dp = 24.dp) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Small glass capsule with text, e.g. "1.25×" or "精听". */
@Composable
fun GlassPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Listenly.colors.text, height: Dp = 40.dp, description: String? = null) {
    GlassBox(
        onClick = onClick,
        shape = ContinuousShape(height / 2),
        modifier = modifier.height(height).semantics { if (description != null) contentDescription = description },
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

/** Filter / display chip: accent-filled when [selected], clear glass otherwise. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Listenly.colors
    val shape = ContinuousShape(16.dp)
    val base = modifier.height(32.dp)
    if (selected) {
        Box(
            base.background(colors.accent, shape)
                .clickable(role = Role.Tab, onClick = onClick)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onAccent) }
    } else {
        GlassBox(onClick = onClick, shape = shape, role = Role.Tab, modifier = base) {
            Text(text, fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 14.dp))
        }
    }
}

/**
 * Segmented control on clear glass with a sliding, springy indicator ("精听/做题", dark mode).
 */
@Composable
fun GlassSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
) {
    val colors = Listenly.colors
    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .liquidGlass(ContinuousShape(height / 2))
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * selected, spring(dampingRatio = 0.62f, stiffness = 420f), label = "segment")
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxSize()
                .background(if (colors.isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.8f), ContinuousShape((height - 8.dp) / 2)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == selected) colors.text else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Seek bar in the HyperOS / iOS 26 manner. At rest the knob is a solid capsule; while dragging it
 * swells into a clear Liquid Glass lens that magnifies and refracts the track beneath it, with
 * colour fringing at the rim. The playhead is read every frame from [position] (two-tier timing)
 * and only drives the draw/layer phases, so playback never recomposes the screen.
 * [marks] are article/sentence boundaries in seconds.
 */
@Composable
fun SeekBar(
    position: () -> Double,
    duration: Double,
    playing: Boolean,
    onSeek: (Double) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    marks: List<Double> = emptyList(),
    trackHeight: Dp = 6.dp,
    /** Coarse position updates, so seeks while paused still move the bar. */
    coarse: Flow<Double>? = null,
) {
    val colors = Listenly.colors
    var width by remember { mutableFloatStateOf(1f) }
    var drawn by remember { mutableFloatStateOf(0f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentDuration by rememberUpdatedState(duration)
    val currentSeek by rememberUpdatedState(onSeek)
    val grab by animateFloatAsState(
        if (dragFraction != null) 1f else 0f,
        spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "grab",
    )

    LaunchedEffect(playing, duration) {
        drawn = fraction(position(), duration)
        while (playing) {
            withFrameNanos { }
            if (dragFraction == null) drawn = fraction(position(), currentDuration)
        }
    }
    val latestPlaying by rememberUpdatedState(playing)
    LaunchedEffect(coarse, duration) {
        drawn = fraction(position(), duration)
        coarse?.collect { if (!latestPlaying && dragFraction == null) drawn = fraction(it, currentDuration) }
    }

    val density = LocalDensity.current
    val thumbW = 38.dp
    val thumbH = 24.dp
    val shaders = isRuntimeShaderSupported() && LocalPageBackdrop.current != null
    // What the glass knob refracts: the card colour plus the track, recorded once per frame.
    val cardColor = if (colors.isDark) Color(0xFF192027) else Color(0xFFEAEFEF)
    val trackLayer = if (shaders) rememberLayerBackdrop { drawRect(cardColor); drawContent() } else null
    val specular = LocalSpecular.current
    val knob = if (colors.isDark) Color(0xFFE9EAEE) else Color.White

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(drawn, 0f..1f)
                setProgress { target -> currentSeek(target.coerceIn(0f, 1f) * currentDuration); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> currentSeek((offset.x / width).coerceIn(0f, 1f) * currentDuration) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onScrubbing(true)
                        dragFraction = (offset.x / width).coerceIn(0f, 1f)
                        drawn = dragFraction!!
                    },
                    onDragEnd = {
                        dragFraction?.let { currentSeek(it * currentDuration) }
                        dragFraction = null
                        onScrubbing(false)
                    },
                    onDragCancel = {
                        dragFraction = null
                        onScrubbing(false)
                    },
                ) { change, amount ->
                    change.consume()
                    dragFraction = ((dragFraction ?: drawn) + amount / width).coerceIn(0f, 1f)
                    drawn = dragFraction!!
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .then(if (trackLayer != null) Modifier.layerBackdrop(trackLayer) else Modifier)
                .drawBehind {
                    // The track thickens a little while grabbed, like a MIUI slider.
                    val h = trackHeight.toPx() * (1f + 0.35f * grab)
                    val y = size.height / 2 - h / 2
                    val radius = CornerRadius(h / 2)
                    drawRoundRect(colors.text.copy(alpha = if (colors.isDark) 0.14f else 0.1f), Offset(0f, y), Size(size.width, h), radius)
                    drawRoundRect(colors.accent, Offset(0f, y), Size((size.width * drawn).coerceAtLeast(h), h), radius)
                    if (currentDuration > 0) for (mark in marks) {
                        val x = (mark / currentDuration * size.width).toFloat()
                        val played = x <= size.width * drawn
                        drawCircle(
                            if (played) colors.onAccent.copy(alpha = 0.7f) else colors.text.copy(alpha = 0.28f),
                            radius = 1.4.dp.toPx(),
                            center = Offset(x, size.height / 2),
                        )
                    }
                },
        )
        val thumbShape = ContinuousCapsule
        Box(
            Modifier
                .graphicsLayer {
                    val w = with(density) { thumbW.toPx() }
                    translationX = (drawn * width - w / 2).coerceIn(0f, (width - w).coerceAtLeast(0f))
                    val s = 1f + 0.45f * grab
                    scaleX = s
                    scaleY = s
                }
                .size(thumbW, thumbH)
                .dropShadow(thumbShape, Shadow(radius = 8.dp, color = Color.Black, alpha = if (colors.isDark) 0.45f else 0.18f, offset = DpOffset(0.dp, 2.dp)))
                .then(
                    if (trackLayer != null) {
                        Modifier.drawBackdrop(
                            backdrop = trackLayer,
                            shape = { thumbShape },
                            effects = {
                                val g = grab
                                padding = maxOf(padding, 20.dp.toPx())
                                lens(
                                    refractionHeight = (thumbH / 2).toPx(),
                                    refractionAmount = (6.dp + 14.dp * g).toPx(),
                                    depthEffect = true,
                                    chromaticAberration = 0.45f * g,
                                )
                            },
                            highlight = specular?.let { sp -> { sp.value.copy(alpha = grab) } },
                            onDrawSurface = {
                                // Solid knob at rest, clear glass while held.
                                drawRect(knob.copy(alpha = 1f - 0.88f * grab))
                            },
                        )
                    } else {
                        Modifier.background(knob, thumbShape)
                    },
                ),
        )
    }
}

private fun fraction(position: Double, duration: Double): Float =
    if (duration <= 0) 0f else (position / duration).toFloat().coerceIn(0f, 1f)

/** Fixed-width three-column stat strip used on Home. */
@Composable
fun StatStrip(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val colors = Listenly.colors
    GlassCard(modifier, padding = PaddingValues(vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, (value, label) ->
                if (index > 0) Box(Modifier.width(1.dp).height(40.dp).background(colors.hairline).align(Alignment.CenterVertically))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                    Text(label, fontSize = 12.sp, color = colors.textSecondary)
                }
            }
        }
    }
}

fun Double.roundTo1(): String = ((this * 10).roundToInt() / 10.0).toString()
