package app.pipo.nativeapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.DistillProgress
import app.pipo.nativeapp.data.PipoGraph

/**
 * 蒸馏后台浮条 —— 顶部居中，跨所有 route 显示，不阻挡用户操作。
 *
 *   - 只在 DistillCoordinator.running = true 时出现
 *   - 一行 12sp 进度文案 + 一条 mint 进度条 + 点击可取消
 *   - 完成后自动 fade out，错误信息显示 6s
 *
 * 不用 fillMaxSize 也不用 background overlay —— 不挡任何交互。
 */
@Composable
fun BoxScopeAnchor() = Unit

@Composable
fun DistillStatusChip() {
    val coord = PipoGraph.distillCoordinator
    val running by coord.running.collectAsState()
    val progress by coord.progress.collectAsState()
    val error by coord.error.collectAsState()

    val visible = running || error != null
    Box(
        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(180)) { -it } + fadeOut(tween(180)),
        ) {
            Row(
                modifier = Modifier
                    .padding(top = 6.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xE6151719))
                    .clickable {
                        // 点击：跑着就取消；错完就 dismiss
                        if (running) coord.cancel() else coord.dismissError()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (running) {
                    LinearProgressIndicator(
                        color = PipoColors.Mint,
                        trackColor = Color(0x22FFFFFF),
                        modifier = Modifier
                            .width(40.dp)
                            .height(2.5.dp)
                            .clip(CircleShape),
                    )
                    Text(
                        text = chipLabel(progress),
                        color = PipoColors.Ink,
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "取消",
                        color = Color(0x99FFFFFF),
                        style = TextStyle(fontSize = 11.sp),
                    )
                } else if (error != null) {
                    Text(
                        text = "蒸馏失败：${error?.take(40) ?: ""}",
                        color = Color(0xFFFFB4B4),
                        style = TextStyle(fontSize = 12.sp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "知道了",
                        color = Color(0x99FFFFFF),
                        style = TextStyle(fontSize = 11.sp),
                    )
                }
            }
        }
    }
}

private fun chipLabel(p: DistillProgress?): String = when (p) {
    is DistillProgress.LoadingTracks -> "蒸馏 · 拉曲目 ${p.done}/${p.total}"
    DistillProgress.Sampling -> "蒸馏 · 分层采样"
    DistillProgress.CallingAi -> "蒸馏 · AI 写画像"
    is DistillProgress.TaggingTracks -> "蒸馏 · 单曲打标 ${p.done}/${p.total}"
    is DistillProgress.EmbeddingTracks -> "蒸馏 · 向量索引 ${p.done}/${p.total}"
    DistillProgress.Done -> "蒸馏 · 完成"
    null -> "蒸馏中…"
}
