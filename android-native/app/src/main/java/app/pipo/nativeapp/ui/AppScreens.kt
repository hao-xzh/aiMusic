package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 子页面用的返回回调 —— PipoNativeApp 在 push 进入前提供，返回到 Player root。 */
val LocalOnBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Navigation shared by the current app pages. */
data class PipoNav(
    val openTaste: () -> Unit,
    val openSettings: () -> Unit,
    val openLogin: () -> Unit,
    val openAiSettings: () -> Unit = {},
)
val LocalNav = staticCompositionLocalOf<PipoNav?> { null }

@Composable
internal fun ScreenScaffold(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    // header 钉在顶部（不滚动），下面 content 单独 scroll —— 跟歌单页一致
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PipoColors.Bg0)
            .statusBarsPadding(),
    ) {
        // 36 / 1fr / 36 网格 header（固定在顶部）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val onBack = LocalOnBack.current
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (onBack != null) {
                    TextButton(
                        onClick = onBack,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("←", color = PipoColors.Ink, style = TextStyle(fontSize = 22.sp))
                    }
                }
            }
            Text(
                text = title,
                color = PipoColors.Ink,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(modifier = Modifier.size(36.dp))
        }

        // 内容滚动区
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 60.dp),
            content = content,
        )
    }
}

@Composable
internal fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            color = PipoColors.TextDim,
            style = TextStyle(fontSize = 13.sp),
        )
    }
}
