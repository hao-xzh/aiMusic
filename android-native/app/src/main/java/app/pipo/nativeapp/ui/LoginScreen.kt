package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.PipoGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 登录页 —— 镜像 src/app/login/page.tsx 的 Android 默认（手机号 + 验证码）路径。
 *
 *   - 手机号 + 验证码（默认）
 *   - 60s 重发倒计时
 *   - 已登录后切回上一页
 */
@Composable
fun LoginScreen(onBack: () -> Unit) {
    val repository = PipoGraph.repository
    val account by repository.account.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var phone by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var cooldown by remember { mutableStateOf(0) }

    LaunchedEffect(account) {
        if (account != null) onBack()
    }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PipoColors.Bg0)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
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
            "登录网易云，把 14 年的歌单接进来。",
            color = PipoColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        Spacer(modifier = Modifier.height(28.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter(Char::isDigit).take(11) },
            label = { Text("手机号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = captcha,
                onValueChange = { captcha = it.filter(Char::isDigit).take(6) },
                label = { Text("验证码") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.size(10.dp))
            TextButton(
                enabled = !sending && cooldown == 0 && phone.length == 11,
                onClick = {
                    sending = true
                    scope.launch {
                        val sent = repository.sendPhoneCaptcha(phone = phone)
                        status = sent.message ?: "验证码已发"
                        if (sent.code == 200) cooldown = 60
                        sending = false
                    }
                },
            ) {
                Text(
                    if (cooldown > 0) "${cooldown}s" else "发送验证码",
                    color = PipoColors.Mint,
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        TextButton(
            enabled = !verifying && phone.length == 11 && captcha.isNotBlank(),
            onClick = {
                verifying = true
                scope.launch {
                    val s = repository.loginWithPhone(phone = phone, captcha = captcha)
                    status = s.nickname?.let { "已登录 · $it" } ?: s.message ?: "登录失败"
                    repository.refreshAccount()
                    verifying = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (verifying) "登录中…" else "登录", color = PipoColors.Gold)
        }

        status?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                it,
                color = PipoColors.TextDim,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}
