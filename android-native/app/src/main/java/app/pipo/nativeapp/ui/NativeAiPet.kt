package app.pipo.nativeapp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.PipoGraph
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class PetMessage(
    val fromUser: Boolean,
    val text: String,
)

@Composable
fun NativeAiPet(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val repository = PipoGraph.repository
    val settings by repository.settings.collectAsState(initial = NativeSettings())
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<PetMessage>() }
    var open by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val phase by rememberInfiniteTransition(label = "pet").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isPlaying) 2600 else 5200,
                easing = LinearEasing,
            ),
        ),
        label = "petPhase",
    )

    LaunchedEffect(messages.size, pending) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (open) {
            PetPanel(
                messages = messages,
                pending = pending,
                input = input,
                onInputChange = { input = it },
                onClose = { open = false },
                onSend = {
                    val text = input.trim()
                    input = ""
                    messages += PetMessage(fromUser = true, text = text)
                    pending = true
                    scope.launch {
                        val system = buildString {
                            append("You are Pipo, a concise music companion inside a native Android music app.")
                            if (settings.userFacts.isNotBlank()) {
                                append("\nUser facts: ")
                                append(settings.userFacts)
                            }
                        }
                        val reply = repository.aiChat(
                            system = system,
                            user = text,
                            temperature = 0.8f,
                            maxTokens = 220,
                        )
                        messages += PetMessage(fromUser = false, text = reply)
                        pending = false
                    }
                },
                scrollModifier = Modifier.verticalScroll(scroll),
            )
        }

        PetOrb(
            phase = phase,
            isOpen = open,
            onClick = { open = !open },
        )
    }
}

@Composable
private fun PetPanel(
    messages: List<PetMessage>,
    pending: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onClose: () -> Unit,
    onSend: () -> Unit,
    scrollModifier: Modifier,
) {
    Column(
        modifier = Modifier
            .offset(y = (-76).dp)
            .widthIn(min = 280.dp, max = 340.dp)
            .heightIn(max = 430.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xEE10151D))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Pipo", color = PipoColors.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (pending) "Thinking" else "Awake",
                    color = PipoColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = PipoColors.TextDim)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 260.dp)
                .then(scrollModifier)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                Text("Here. Tell me.", color = PipoColors.TextDim)
            }
            messages.forEach { message ->
                Text(
                    text = message.text,
                    color = if (message.fromUser) PipoColors.Mint else PipoColors.Text,
                    modifier = Modifier
                        .align(if (message.fromUser) Alignment.End else Alignment.Start)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (message.fromUser) Color(0x222DE0A8) else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (pending) {
                Text("...", color = PipoColors.TextDim)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Say something") },
            )
            TextButton(
                enabled = input.isNotBlank() && !pending,
                onClick = onSend,
            ) {
                Icon(Icons.Rounded.Send, contentDescription = "Send", tint = PipoColors.Mint)
            }
        }
    }
}

@Composable
private fun PetOrb(
    phase: Float,
    isOpen: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(if (isOpen) 66.dp else 62.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(PipoColors.Mint.copy(alpha = 0.78f), PipoColors.Blue.copy(alpha = 0.24f)),
                ),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(62.dp)) {
            rotate(degrees = phase * 360f) {
                repeat(8) { i ->
                    val a = (i / 8f) * 2f * PI.toFloat() + phase * 0.6f
                    drawCircle(
                        color = Color.White.copy(alpha = 0.12f + i * 0.012f),
                        radius = (2.2f + i * 0.22f).dp.toPx(),
                        center = Offset(
                            center.x + cos(a) * size.minDimension * 0.32f,
                            center.y + sin(a) * size.minDimension * 0.32f,
                        ),
                    )
                }
            }
        }
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = "Pipo",
            tint = PipoColors.Background,
            modifier = Modifier.size(25.dp),
        )
    }
}
