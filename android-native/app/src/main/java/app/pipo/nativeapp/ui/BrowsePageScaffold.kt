package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BrowsePageScaffold(title: String, coverUrl: String? = null, artistic: Boolean = false, onBack: (() -> Unit)? = LocalOnBack.current, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(BrowseBackground)) {
        if (coverUrl != null) PlaylistDetailBackdrop(coverUrl, useCoverEdgeColors(coverUrl), artistic, 400.dp, 0f, darkSurface = true)
        Box(Modifier.fillMaxSize().background(BrowseBackground.copy(alpha = if (artistic) 0.42f else 0.8f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onBack?.invoke() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = BrowseInk) }
                Text("PIPO", color = BrowseInk, fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
            if (artistic) Spacer(Modifier.height(110.dp))
            if (title.isNotEmpty()) Text(title, color = BrowseInk, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 18.dp))
            content()
        }
    }
}
