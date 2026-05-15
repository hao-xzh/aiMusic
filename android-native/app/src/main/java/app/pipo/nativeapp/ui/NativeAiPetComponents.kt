package app.pipo.nativeapp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HintBubble(text: String) {
    if (text.isBlank()) return
    Box(
        modifier = Modifier
            .padding(bottom = 8.dp, end = 4.dp)
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC0A0D14))
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x18FFFFFF), Color.Transparent),
                        startY = 0f,
                        endY = 18f,
                    ),
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
        )
    }
}

@Composable
internal fun ReplyBubble(text: String?, tint: Color) {
    if (text != null && text.isBlank()) return
    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE60A0D14))
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(tint.copy(alpha = 0.22f), Color.Transparent),
                        startY = 0f,
                        endY = 24f,
                    ),
                )
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text == null) {
            ThinkingDots(tint = tint)
        } else {
            Text(
                text = text,
                color = Color(0xF2F5F7FF),
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
    coverUrl: String?,
    input: String,
    pending: Boolean,
    hintText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val edges = useCoverEdgeColors(coverUrl)
    val tintColor = rgbToColor(edges.right, fallback = PipoColors.Mint)
    val tintTop = rgbToColor(edges.top, fallback = PipoColors.Mint)
    val canSend = input.isNotBlank() && !pending

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xE60A0D14))
            .drawBehind {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            tintTop.copy(alpha = 0.14f),
                            Color.Transparent,
                            tintColor.copy(alpha = 0.14f),
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
                    color = PipoColors.Ink,
                    fontSize = 14.sp,
                    letterSpacing = 0.15.sp,
                ),
                cursorBrush = SolidColor(tintColor.copy(alpha = 0.85f)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (input.isNotBlank() && !pending) onSend() },
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (input.isEmpty()) {
                        Text(
                            text = hintText,
                            color = Color(0x80FFFFFF),
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
                    if (canSend) tintColor.copy(alpha = 0.85f)
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
                tint = if (canSend) Color(0xFF0A0D14) else Color(0x99FFFFFF),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PetFaceMini(modifier: Modifier = Modifier, pending: Boolean = false) {
    val faceCream = Color(0xFFE0D9C4)
    val faceInk = Color(0xFF1B1815)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(faceCream),
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
                color = faceInk,
                topLeft = Offset(w * 0.34f - eyeW / 2, eyeY - eyeH / 2),
                size = Size(eyeW, eyeH),
            )
            drawOval(
                color = faceInk,
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
                color = faceInk,
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
    onClick: () -> Unit,
) {
    val haloScale = 1f + haloPulse * 0.3f
    val haloAlpha = 0.4f + (1f - haloPulse) * 0.6f
    val orbSize = 36.dp
    val ropeHeight = 14.dp
    val faceCream = Color(0xFFE0D9C4)
    val faceInk = Color(0xFF1B1815)

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
                        Color(0x00FFFFFF),
                        Color(0xCCFFFFFF),
                        Color(0xCCFFFFFF),
                        Color(0x00FFFFFF),
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
                                faceCream.copy(alpha = 0.45f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .size(orbSize)
                    .clip(CircleShape)
                    .background(faceCream)
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
                        color = faceInk,
                        topLeft = Offset(leftEyeCx - eyeW / 2, eyeY - eyeH / 2),
                        size = Size(eyeW, eyeH),
                    )
                    drawOval(
                        color = faceInk,
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
                        color = faceInk,
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
