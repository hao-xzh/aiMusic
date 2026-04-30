package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val PipoDarkColors = darkColorScheme(
    background = PipoColors.Background,
    surface = PipoColors.Surface,
    primary = PipoColors.Mint,
    secondary = PipoColors.Blue,
    tertiary = PipoColors.Gold,
    onBackground = PipoColors.Text,
    onSurface = PipoColors.Text,
    onPrimary = Color(0xFF062014),
)

@Composable
fun PipoNativeApp() {
    MaterialTheme(colorScheme = PipoDarkColors) {
        var tab by remember { mutableStateOf(NativeTab.Player) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PipoColors.Background),
        ) {
            when (tab) {
                NativeTab.Player -> PlayerScreen()
                NativeTab.Taste -> TasteScreen()
                NativeTab.Distill -> DistillScreen()
                NativeTab.Settings -> SettingsScreen()
            }
            NativeBottomNav(
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            NativeAiPet(
                isPlaying = tab == NativeTab.Player,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 18.dp, bottom = 76.dp),
            )
        }
    }
}

private enum class NativeTab(
    val label: String,
    val icon: ImageVector,
) {
    Player("Play", Icons.Rounded.GraphicEq),
    Taste("Taste", Icons.Rounded.AutoAwesome),
    Distill("Mix", Icons.Rounded.LibraryMusic),
    Settings("Settings", Icons.Rounded.Settings),
}

@Composable
private fun NativeBottomNav(
    selected: NativeTab,
    onSelect: (NativeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NativeTab.entries.forEach { tab ->
            val active = selected == tab
            IconButton(
                onClick = { onSelect(tab) },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (active) PipoColors.Text else Color(0x12FFFFFF)),
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (active) PipoColors.Background else PipoColors.TextMuted,
                )
            }
        }
    }
}
