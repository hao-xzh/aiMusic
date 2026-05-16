package app.pipo.nativeapp.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.PipoGraph
import kotlinx.coroutines.delay

/**
 * 登录页 —— 网易云 App 扫码登录。
 *
 * 走原生 weapi qr_start + qr_check 轮询。code 含义:
 *   800 = 二维码过期
 *   801 = 等待扫码
 *   802 = 等待手机端确认
 *   803 = 授权成功 → cookie 已存,refreshAccount 拿到账号 → onBack 回上一页
 *
 * 之前这页是手机号 + 验证码方式,网易经常风控被拒,体验差且跟设置页扫码方式割裂。
 * 统一只保留扫码。
 */
@Composable
fun LoginScreen(onBack: () -> Unit) {
    val repository = PipoGraph.repository
    val account by repository.account.collectAsState(initial = null)

    var qrContent by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("点下面用网易云 App 扫码") }
    var qrRefreshNonce by remember { mutableIntStateOf(0) }

    // 已登录后切回上一页
    LaunchedEffect(account) {
        if (account != null) onBack()
    }

    // 进入页面立刻拉一张二维码,不让用户多按一次按钮
    // nonce 变化时 LaunchedEffect 会自动 cancel 上一次轮询，避免旧二维码覆盖新二维码状态。
    LaunchedEffect(qrRefreshNonce) {
        runQrFlow(repository, onContent = { qrContent = it }, onStatus = { status = it })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PipoColors.Bg0)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("←", color = PipoColors.Ink, style = TextStyle(fontSize = 22.sp))
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "PIPO",
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 6.sp),
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "登录网易云,把 14 年的歌单接进来。",
            color = PipoColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        Spacer(modifier = Modifier.height(36.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            qrContent?.let { content ->
                QrCode(
                    content = content,
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            status,
            color = PipoColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(
                onClick = { qrRefreshNonce += 1 },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (qrContent == null) "生成二维码" else "刷新二维码",
                    color = PipoColors.Mint,
                )
            }
        }
    }
}

/**
 * 跑一轮扫码:start → 60s 内每 2s poll 一次 → 803 成功 / 800 过期 / 超时 都退出。
 * 抽出来共用,也好让"刷新二维码"按钮重复触发。
 */
private suspend fun runQrFlow(
    repository: app.pipo.nativeapp.data.PipoRepository,
    onContent: (String?) -> Unit,
    onStatus: (String) -> Unit,
) {
    onStatus("正在请求二维码…")
    val startResult = runCatching { repository.startQrLogin() }
    val start = startResult.getOrNull()
    if (start == null) {
        onContent(null)
        startResult.exceptionOrNull()?.let { err ->
            Log.w("PipoLogin", "start QR login failed", err)
        }
        onStatus("二维码加载失败，检查网络后刷新")
        return
    }
    if (start.qrContent.isBlank()) {
        onContent(null)
        Log.w("PipoLogin", "start QR login returned blank content")
        onStatus("二维码暂时不可用，请刷新重试")
        return
    }
    onContent(start.qrContent)
    onStatus("用网易云 App 扫上面这个码")
    repeat(30) {
        delay(2_000)
        val checkResult = runCatching { repository.checkQrLogin(start.key) }
        val s = checkResult.getOrNull()
        if (s == null) {
            checkResult.exceptionOrNull()?.let { err ->
                Log.w("PipoLogin", "check QR login failed", err)
            }
            onStatus("登录状态获取失败，稍等后刷新")
            onContent(null)
            return
        }
        when (s.code) {
            801 -> onStatus("等待扫码…")
            802 -> onStatus("已扫码,在手机上确认登录")
            803 -> {
                onStatus(s.nickname?.let { "已登录 · $it" } ?: "登录成功")
                onContent(null)
                runCatching { repository.refreshAccount() }
                return
            }
            800 -> {
                onStatus("二维码已过期 —— 点刷新重新生成")
                onContent(null)
                return
            }
            else -> {
                onStatus(s.message ?: "状态码 ${s.code}")
                if (s.code < 0) {
                    onContent(null)
                    return
                }
            }
        }
    }
    // 60s 还没扫完
    onStatus("二维码超时 —— 点刷新重新生成")
    onContent(null)
}
