package app.pipo.nativeapp.ui

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.R

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
    val accent = normalizePetAccent(rgbToColor(pickPetAccent(edges), fallback = PipoColors.Mint))
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
        val rescue = if (relativeLuma(out) > 0.5f) PipoColors.Blue else PipoColors.Mint
        out = mixColors(out, rescue, 0.48f)
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
                    val eyeH = s * 0.16f
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
