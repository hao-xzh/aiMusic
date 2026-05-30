package app.pipo.nativeapp.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.R
import coil.compose.AsyncImage

internal data class PetPalette(
    val accent: Color,
    val accentTop: Color,
    val face: Color,
    val faceInk: Color,
    val halo: Color,
    val rope: Color,
    val panel: Color,
    val panelText: Color,
    val panelTextDim: Color,
    val actionText: Color,
)

@Composable
internal fun rememberPetPalette(edges: EdgeColors): PetPalette {
    val raw = remember(edges) { buildPetPalette(edges) }
    val accent by animateColorAsState(raw.accent, tween(520), label = "petAccent")
    val accentTop by animateColorAsState(raw.accentTop, tween(520), label = "petAccentTop")
    val face by animateColorAsState(raw.face, tween(520), label = "petFace")
    val halo by animateColorAsState(raw.halo, tween(520), label = "petHalo")
    val rope by animateColorAsState(raw.rope, tween(520), label = "petRope")
    val panel by animateColorAsState(raw.panel, tween(520), label = "petPanel")
    val panelText = if (relativeLuma(panel) > 0.58f) Color(0xF20A0D14) else Color(0xF2F5F7FF)
    val panelTextDim = panelText.copy(alpha = 0.62f)
    val actionText = if (relativeLuma(accent) > 0.58f) Color(0xFF0A0D14) else Color(0xFFF5F7FF)
    return PetPalette(
        accent = accent,
        accentTop = accentTop,
        face = face,
        faceInk = if (relativeLuma(face) > 0.52f) Color(0xFF1B1815) else Color(0xFFF5F7FF),
        halo = halo,
        rope = rope,
        panel = panel,
        panelText = panelText,
        panelTextDim = panelTextDim,
        actionText = actionText,
    )
}

private fun buildPetPalette(edges: EdgeColors): PetPalette {
    val accent = normalizePetAccent(rgbToColor(pickPetAccent(edges), fallback = Color(0xFFC8C3BA)))
    val top = normalizePetAccent(rgbToColor(edges.top, fallback = accent))
    val faceBase = Color(0xFFE0D9C4)
    val liftedAccent = when {
        relativeLuma(accent) < 0.28f -> mixColors(accent, Color.White, 0.58f)
        relativeLuma(accent) > 0.78f -> mixColors(accent, PipoColors.Bg1, 0.24f)
        else -> accent
    }
    val face = mixColors(faceBase, liftedAccent, 0.76f)
    val panel = mixColors(PipoColors.Bg1, accent, 0.30f).copy(alpha = 0.92f)
    return PetPalette(
        accent = accent,
        accentTop = top,
        face = face,
        faceInk = if (relativeLuma(face) > 0.52f) Color(0xFF1B1815) else Color(0xFFF5F7FF),
        halo = mixColors(accent, face, 0.34f),
        rope = mixColors(top, Color.White, 0.45f),
        panel = panel,
        panelText = Color(0xF2F5F7FF),
        panelTextDim = Color(0x99FFFFFF),
        actionText = if (relativeLuma(accent) > 0.58f) Color(0xFF0A0D14) else Color(0xFFF5F7FF),
    )
}

private fun pickPetAccent(edges: EdgeColors): IntArray? {
    return listOfNotNull(edges.right, edges.bottom, edges.top)
        .maxByOrNull { rgb ->
            val luma = rgbLuma(rgb)
            val sat = rgbSaturation(rgb)
            val usableLight = 1f - kotlin.math.abs(luma - 0.56f) / 0.56f
            sat * 0.62f + usableLight.coerceIn(0f, 1f) * 0.38f
        }
}

private fun normalizePetAccent(color: Color): Color {
    var out = color
    val luma = relativeLuma(out)
    out = when {
        luma < 0.18f -> mixColors(out, Color.White, 0.46f)
        luma > 0.86f -> mixColors(out, PipoColors.Bg1, 0.22f)
        else -> out
    }
    if (colorSaturation(out) < 0.08f) {
        // 灰 / 黑白封面:不注入薄荷绿,保持中性(略提亮成可用的暖中性色)
        out = mixColors(out, Color(0xFFC8C3BA), 0.40f)
    }
    return out
}

private fun mixColors(a: Color, b: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    val inv = 1f - t
    return Color(
        red = a.red * inv + b.red * t,
        green = a.green * inv + b.green * t,
        blue = a.blue * inv + b.blue * t,
        alpha = a.alpha * inv + b.alpha * t,
    )
}

private fun relativeLuma(color: Color): Float {
    return (0.299f * color.red + 0.587f * color.green + 0.114f * color.blue).coerceIn(0f, 1f)
}

private fun colorSaturation(color: Color): Float {
    val max = maxOf(color.red, color.green, color.blue)
    val min = minOf(color.red, color.green, color.blue)
    return if (max <= 0f) 0f else (max - min) / max
}

private fun rgbLuma(rgb: IntArray): Float {
    if (rgb.size < 3) return 0.5f
    return ((0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]) / 255f).coerceIn(0f, 1f)
}

private fun rgbSaturation(rgb: IntArray): Float {
    if (rgb.size < 3) return 0f
    val r = rgb[0] / 255f
    val g = rgb[1] / 255f
    val b = rgb[2] / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    return if (max <= 0f) 0f else (max - min) / max
}

@Composable
internal fun HintBubble(text: String, palette: PetPalette) {
    if (text.isBlank()) return
    Box(
        modifier = Modifier
            .padding(bottom = 8.dp, end = 4.dp)
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.panel)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(palette.accent.copy(alpha = 0.24f), Color.Transparent),
                        startY = 0f,
                        endY = 18f,
                    ),
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = palette.panelText,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
        )
    }
}

@Composable
internal fun CoverAiCaption(text: String, palette: PetPalette) {
    if (text.isBlank()) return
    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(palette.panel.copy(alpha = 0.78f))
            .padding(start = 7.dp, end = 11.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_round),
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape),
        )
        Text(
            text = text,
            color = palette.panelText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
internal fun ReplyBubble(text: String?, palette: PetPalette) {
    if (text != null && text.isBlank()) return
    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.panel)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(palette.accent.copy(alpha = 0.24f), Color.Transparent),
                        startY = 0f,
                        endY = 24f,
                    ),
                )
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text == null) {
            ThinkingDots(tint = palette.accent)
        } else {
            Text(
                text = text,
                color = palette.panelText,
                style = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.15.sp,
                ),
            )
        }
    }
}

@Composable
private fun ThinkingDots(tint: Color) {
    val transition = rememberInfiniteTransition(label = "thinkingDots")

    @Composable
    fun dotPhase(delayMs: Int): Float {
        val v by transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(540, delayMillis = delayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot$delayMs",
        )
        return v
    }

    val a = dotPhase(0)
    val b = dotPhase(180)
    val c = dotPhase(360)

    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(a, b, c).forEach { phase ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        alpha = phase
                        val s = 0.75f + phase * 0.25f
                        scaleX = s
                        scaleY = s
                    }
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.85f)),
            )
        }
    }
}

@Composable
internal fun PetCommandBar(
    palette: PetPalette,
    input: String,
    pending: Boolean,
    hintText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = input.isNotBlank() && !pending
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(palette.panel)
            .drawBehind {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            palette.accentTop.copy(alpha = 0.16f),
                            Color.Transparent,
                            palette.accent.copy(alpha = 0.16f),
                        ),
                    ),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x1FFFFFFF), Color.Transparent),
                        startY = 0f,
                        endY = 16f,
                    ),
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PetFaceMini(
            modifier = Modifier.size(32.dp),
            pending = pending,
            palette = palette,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = palette.panelText,
                    fontSize = 14.sp,
                    letterSpacing = 0.15.sp,
                ),
                cursorBrush = SolidColor(palette.accent.copy(alpha = 0.85f)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (input.isNotBlank() && !pending) onSend() },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    if (input.isEmpty()) {
                        Text(
                            text = hintText,
                            color = palette.panelTextDim,
                            style = TextStyle(
                                fontSize = 14.sp,
                                letterSpacing = 0.15.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                },
            )
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) palette.accent.copy(alpha = 0.88f)
                    else Color(0x14FFFFFF),
                )
                .clickable(
                    enabled = canSend,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSend,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = "发送",
                tint = if (canSend) palette.actionText else palette.panelTextDim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PetFaceMini(modifier: Modifier = Modifier, pending: Boolean = false, palette: PetPalette) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(palette.face),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val s = size.minDimension
            val eyeW = s * 0.10f
            val eyeH = if (pending) s * 0.04f else s * 0.16f
            val eyeY = h * 0.38f
            drawOval(
                color = palette.faceInk,
                topLeft = Offset(w * 0.34f - eyeW / 2, eyeY - eyeH / 2),
                size = Size(eyeW, eyeH),
            )
            drawOval(
                color = palette.faceInk,
                topLeft = Offset(w * 0.66f - eyeW / 2, eyeY - eyeH / 2),
                size = Size(eyeW, eyeH),
            )
            val mouthY = h * 0.62f
            val xL = w * 0.22f
            val xR = w * 0.78f
            val smile = Path().apply {
                moveTo(xL, mouthY + s * 0.02f)
                cubicTo(
                    w * 0.30f, mouthY + s * 0.13f,
                    w * 0.42f, mouthY + s * 0.16f,
                    w * 0.54f, mouthY + s * 0.07f,
                )
                cubicTo(
                    w * 0.62f, mouthY - s * 0.01f,
                    w * 0.72f, mouthY - s * 0.04f,
                    xR, mouthY + s * 0.01f,
                )
            }
            drawPath(
                path = smile,
                color = palette.faceInk,
                style = Stroke(
                    width = s * 0.075f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

@Composable
internal fun PetOrb(
    haloPulse: Float,
    sway: Float,
    pulseScale: Float,
    attached: Boolean,
    palette: PetPalette,
    pending: Boolean = false,
    onClick: () -> Unit,
) {
    val haloScale = 1f + haloPulse * 0.3f
    val haloAlpha = 0.4f + (1f - haloPulse) * 0.6f
    val orbSize = 36.dp
    val ropeHeight = 14.dp

    Column(
        modifier = Modifier.graphicsLayer {
            rotationZ = sway
            transformOrigin = TransformOrigin(0.5f, 0f)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .width(6.dp)
                .height(ropeHeight)
                .graphicsLayer { alpha = if (attached) 0.55f else 0f },
        ) {
            val cx = size.width / 2f
            val strokePx = 1.6.dp.toPx()
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        palette.rope.copy(alpha = 0f),
                        palette.rope.copy(alpha = 0.82f),
                        palette.rope.copy(alpha = 0.82f),
                        palette.rope.copy(alpha = 0f),
                    ),
                    startY = 0f,
                    endY = size.height,
                ),
                start = Offset(cx, 0f),
                end = Offset(cx, size.height),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }

        Box(
            modifier = Modifier
                .size(orbSize)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(orbSize + 22.dp)
                    .graphicsLayer {
                        scaleX = haloScale
                        scaleY = haloScale
                        alpha = haloAlpha * 0.45f
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                palette.halo.copy(alpha = 0.72f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .size(orbSize)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                mixColors(palette.face, Color.White, 0.18f),
                                mixColors(palette.face, palette.accent, 0.26f),
                                palette.accent,
                            ),
                        ),
                    )
                    .clickable(onClick = onClick),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val s = size.minDimension

                    val eyeW = s * 0.09f
                    val eyeH = if (pending) s * 0.04f else s * 0.16f
                    val eyeY = h * 0.36f
                    val leftEyeCx = w * 0.34f
                    val rightEyeCx = w * 0.66f
                    drawOval(
                        color = palette.faceInk,
                        topLeft = Offset(leftEyeCx - eyeW / 2, eyeY - eyeH / 2),
                        size = Size(eyeW, eyeH),
                    )
                    drawOval(
                        color = palette.faceInk,
                        topLeft = Offset(rightEyeCx - eyeW / 2, eyeY - eyeH / 2),
                        size = Size(eyeW, eyeH),
                    )

                    val mouthY = h * 0.62f
                    val xL = w * 0.20f
                    val xR = w * 0.80f
                    val smilePath = Path().apply {
                        moveTo(xL, mouthY + s * 0.02f)
                        cubicTo(
                            w * 0.30f, mouthY + s * 0.13f,
                            w * 0.42f, mouthY + s * 0.16f,
                            w * 0.54f, mouthY + s * 0.07f,
                        )
                        cubicTo(
                            w * 0.62f, mouthY - s * 0.01f,
                            w * 0.72f, mouthY - s * 0.04f,
                            xR, mouthY + s * 0.01f,
                        )
                    }
                    drawPath(
                        path = smilePath,
                        color = palette.faceInk,
                        style = Stroke(
                            width = s * 0.075f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChatHistoryPanel(
    messages: List<PetMessage>,
    pending: Boolean,
    palette: PetPalette,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 280.dp,
    /** 全屏模式:透明背景(背后是真实播放页 + AiChatBackdrop + 实底气泡),不再套一层面板。 */
    transparent: Boolean = false,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, pending) {
        val totalItems = messages.size + (if (pending) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    // 全屏模式(transparent)下不套面板:整屏就是消息容器,消息气泡浮在播放页背景上。
    val framed = !transparent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .then(
                if (framed) {
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.panel)
                        .drawBehind {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(palette.accent.copy(alpha = 0.18f), Color.Transparent),
                                    startY = 0f,
                                    endY = 32f,
                                )
                            )
                        }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (framed) 12.dp else 2.dp, vertical = 10.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages.size) { index ->
                val msg = messages[index]
                MessageBubbleItem(msg = msg, palette = palette)
            }
            if (pending) {
                item {
                    ThinkingBubbleItem(palette = palette)
                }
            }
        }
    }
}

@Composable
internal fun MessageBubbleItem(msg: PetMessage, palette: PetPalette) {
    // 结果卡片消息(放歌/收藏/加歌单等)—— 左对齐,助手侧。
    msg.card?.let { card ->
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            when (card) {
                is PetResultCard.Play -> PlayResultCard(card, palette)
                is PetResultCard.Action -> ActionChip(card, palette)
            }
        }
        return
    }
    val alignment = if (msg.fromUser) Alignment.End else Alignment.Start
    val bubbleColor = if (msg.fromUser) {
        if (relativeLuma(palette.accent) > 0.56f) {
            mixColors(palette.accent, Color(0xFF10141E), 0.22f).copy(alpha = 0.96f)
        } else {
            mixColors(palette.accent, Color(0xFFF5F7FF), 0.20f).copy(alpha = 0.96f)
        }
    } else {
        Color(0xF20D111A)
    }
    val textColor = if (msg.fromUser) {
        if (relativeLuma(bubbleColor) > 0.58f) Color(0xFF0A0D14) else Color(0xFFF5F7FF)
    } else {
        Color(0xFFF5F7FF)
    }
    val shape = if (msg.fromUser) {
        RoundedCornerShape(14.dp, 14.dp, 3.dp, 14.dp)
    } else {
        RoundedCornerShape(14.dp, 14.dp, 14.dp, 3.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(shape)
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = msg.text,
                color = textColor,
                style = TextStyle(
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    letterSpacing = 0.1.sp
                )
            )
        }
    }
}

@Composable
internal fun ThinkingBubbleItem(palette: PetPalette) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(14.dp, 14.dp, 14.dp, 3.dp))
                .background(Color(0xF20D111A))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            ThinkingDots(tint = palette.accent)
        }
    }
}

/**
 * AI 全屏对话覆盖层：真实播放页仍在下面，只做轻量压暗和封面色环境光。
 * 播放页 blur 在 PipoNativeApp 统一处理，避免这里再铺一张封面把页面盖掉。
 */
@Composable
internal fun AiChatBackdrop(
    coverUrl: String?,
    palette: PetPalette,
    intensity: Float,
    modifier: Modifier = Modifier,
) {
    if (intensity <= 0.002f) return
    val k = intensity.coerceIn(0f, 1f)
    val hasCover = !coverUrl.isNullOrBlank()
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF05070B).copy(alpha = 0.12f * k),
                        Color(0xFF05070B).copy(alpha = 0.22f * k),
                        Color(0xFF05070B).copy(alpha = 0.38f * k),
                    ),
                    startY = 0f,
                    endY = h,
                ),
                size = size,
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.accent.copy(alpha = (if (hasCover) 0.14f else 0.22f) * k),
                        palette.accent.copy(alpha = (if (hasCover) 0.05f else 0.08f) * k),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.48f, h * 0.76f),
                    radius = maxOf(w, h) * 0.66f,
                ),
                size = size,
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        palette.accentTop.copy(alpha = 0.06f * k),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = h * 0.28f,
                ),
                size = Size(w, h * 0.28f),
            )
        }
    }
}

/**
 * 唤起 AI 时浮在播放界面之上的环境光层:封面色中心柔光 + 四周边缘微光 +
 * 输入区极淡压暗(给文字兜底对比度,不是平涂遮罩)。[intensity] 0→1 驱动入场,
 * [pulse] 接 haloPulse 做呼吸。封面色取自 palette(灰封面已被 normalizePetAccent 兜底成薄荷)。
 */
@Composable
internal fun GlowBackdrop(
    palette: PetPalette,
    intensity: Float,
    modifier: Modifier = Modifier,
) {
    if (intensity <= 0.002f) return
    // 稳态:alpha 只随唤起强度淡入,绝不随节拍脉动 —— 全屏层一脉动就是"整屏闪"。
    val k = intensity.coerceIn(0f, 1f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // 底部柔和压暗:只给输入/消息区文字兜底对比度,从中部向下渐深、顶部全透(非平涂遮罩)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color(0xFF05070B).copy(alpha = 0.34f * k)),
                startY = h * 0.42f,
                endY = h,
            ),
        )
        // 一束封面色柔光,从底部(orb 升起处)绽放 —— 半径随 intensity 由小变大,形成"绽放"入场
        val bloomR = maxOf(w, h) * (0.42f + 0.34f * k)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.accent.copy(alpha = 0.20f * k),
                    palette.accent.copy(alpha = 0.06f * k),
                    Color.Transparent,
                ),
                center = Offset(w * 0.5f, h * 0.74f),
                radius = bloomR,
            ),
            size = size,
        )
        // 顶部一抹更淡同色平衡画面(不做四边光,避免廉价/闪烁感)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(palette.accentTop.copy(alpha = 0.08f * k), Color.Transparent),
                startY = 0f,
                endY = h * 0.3f,
            ),
            size = Size(w, h * 0.3f),
        )
    }
}

/** 放歌结果卡:▶ + 封面缩略条 + "开整 N 首" + 艺人。映射 AgentAction.Play。 */
@Composable
internal fun PlayResultCard(card: PetResultCard.Play, palette: PetPalette) {
    val title = when {
        card.insert -> "插一首"
        card.similar -> "配同款 · ${card.count} 首"
        else -> "开整 · ${card.count} 首"
    }
    Row(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.panel)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(palette.accent.copy(alpha = 0.22f), Color.Transparent),
                        startY = 0f, endY = 26f,
                    ),
                )
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(palette.accent.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", color = palette.actionText, style = TextStyle(fontSize = 15.sp))
        }
        val covers = card.covers.filterNotNull().take(3)
        if (covers.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                covers.forEach { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = palette.panelText,
                style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (card.artists.isNotBlank()) {
                Text(
                    card.artists,
                    color = palette.panelTextDim,
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 单行动作 chip:收藏 / 进出歌单 / 切歌。映射 AgentAction.Like / Playlist / Skip。 */
@Composable
internal fun ActionChip(card: PetResultCard.Action, palette: PetPalette) {
    val bg = if (card.ok) palette.accent.copy(alpha = 0.18f) else Color(0x33FF6B6B)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(card.glyph, style = TextStyle(fontSize = 13.sp))
        Text(
            card.label,
            color = palette.panelText,
            style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
        )
    }
}

/** 空态建议提示 chips(可点直接发送)。 */
@Composable
internal fun SuggestedChips(
    hints: List<String>,
    palette: PetPalette,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        hints.take(3).forEach { h ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.panel.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onPick(h) },
                    )
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            ) {
                Text(
                    h,
                    color = palette.panelText,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }
}
