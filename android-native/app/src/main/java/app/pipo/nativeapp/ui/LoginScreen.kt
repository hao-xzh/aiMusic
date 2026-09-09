package app.pipo.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onBack: () -> Unit) {
    val repository = PipoGraph.repository
    val account by repository.account.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(LoginMode.Phone) }
    var phone by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var cooldown by remember { mutableIntStateOf(0) }
    var loginCooldown by remember { mutableIntStateOf(0) }
    var qrContent by remember { mutableStateOf<String?>(null) }
    var qrRefreshNonce by remember { mutableIntStateOf(0) }
    var qrStatus by remember { mutableStateOf("用网易云 App 扫码确认登录。") }
    val phoneOk = Regex("^1[0-9]{10}$").matches(phone)
    val captchaOk = captcha.length in 4..6
    LaunchedEffect(account) { if (account != null) onBack() }
    LaunchedEffect(cooldown) { if (cooldown > 0) { delay(1_000); cooldown-- } }
    LaunchedEffect(loginCooldown) { if (loginCooldown > 0) { delay(1_000); loginCooldown-- } }
    LaunchedEffect(mode, qrRefreshNonce) {
        if (mode != LoginMode.Qr) { qrContent = null; return@LaunchedEffect }
        runQrFlow(repository, onContent = { qrContent = it }, onStatus = { qrStatus = it })
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BrowseMuted, unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
        focusedContainerColor = Color.White.copy(alpha = 0.025f), unfocusedContainerColor = Color.White.copy(alpha = 0.025f),
        focusedTextColor = BrowseInk, unfocusedTextColor = BrowseInk,
        focusedPlaceholderColor = BrowseMuted, unfocusedPlaceholderColor = BrowseMuted,
        focusedPrefixColor = BrowseInk, unfocusedPrefixColor = BrowseInk, cursorColor = PipoColors.Mint
    )
    BrowsePageScaffold("", coverUrl = "file:///android_asset/artwork/login-music-classics.png", artistic = true, onBack = onBack) {
        Text("旧日有声，\n此刻重逢。", color = BrowseInk, fontSize = 36.sp, lineHeight = 48.sp)
        Text("让网易云里的珍藏，继续陪你往前走。", color = BrowseMuted, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp, bottom = 72.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextButton(enabled = !sending && !loggingIn, onClick = { mode = LoginMode.Phone }) { Text("手机号登录", color = if (mode == LoginMode.Phone) BrowseInk else BrowseMuted) }
            TextButton(enabled = !sending && !loggingIn, onClick = { mode = LoginMode.Qr }) { Text("扫码登录", color = if (mode == LoginMode.Qr) BrowseInk else BrowseMuted) }
        }
        if (mode == LoginMode.Phone) {
            OutlinedTextField(phone, { phone = it.filter { c -> c in '0'..'9' }.take(11) }, Modifier.fillMaxWidth().padding(top = 18.dp), colors = fieldColors, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp), prefix = { Text("+86  ") }, placeholder = { Text("11 位手机号") }, singleLine = true, readOnly = sending || loggingIn, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp))
            OutlinedTextField(captcha, { captcha = it.filter { c -> c in '0'..'9' }.take(6) }, Modifier.fillMaxWidth().padding(top = 18.dp), colors = fieldColors, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp), placeholder = { Text("短信验证码") }, singleLine = true, readOnly = sending || loggingIn, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp), trailingIcon = {
                TextButton(enabled = phoneOk && !sending && !loggingIn && cooldown == 0 && loginCooldown == 0, onClick = {
                    if (sending || loggingIn || cooldown > 0 || loginCooldown > 0) return@TextButton
                    val requestedPhone = phone
                    sending = true
                    cooldown = 60
                    status = "正在发送验证码…"
                    scope.launch {
                        try {
                            val sent = repository.sendPhoneCaptcha(requestedPhone)
                            status = phoneStatus(sent, null)
                            if (sent.code == 200) DiagnosticsLogStore.record("login", "phone_captcha_sent")
                            else DiagnosticsLogStore.record("login", "phone_captcha_failed", mapOf("code" to sent.code))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { status = phoneStatus(null, e) }
                        finally { sending = false }
                    }
                }) { Text(if (sending) "发送中" else if (cooldown > 0) "${cooldown}s" else "发送验证码", fontSize = 12.sp) }
            })
            Button(enabled = phoneOk && captchaOk && !sending && !loggingIn && loginCooldown == 0, onClick = {
                if (sending || loggingIn || loginCooldown > 0) return@Button
                val requestedPhone = phone
                val requestedCaptcha = captcha
                loggingIn = true
                status = "正在登录…"
                scope.launch {
                    try {
                        val result = repository.loginWithPhone(requestedPhone, requestedCaptcha)
                        status = phoneLoginStatus(result, null)
                        if (isPhoneLoginRestricted(result.code, result.message)) loginCooldown = 60
                        if (result.code == 200) { DiagnosticsLogStore.record("login", "phone_login_success"); repository.refreshAccount() }
                        else DiagnosticsLogStore.record("login", "phone_login_failed", mapOf("code" to result.code))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { loginCooldown = 60; status = phoneLoginStatus(null, e) }
                    finally { loggingIn = false }
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 22.dp).heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = BrowseInk, contentColor = Color.Black), shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp)) { Text(if (loggingIn) "登录中…" else if (loginCooldown > 0) "${loginCooldown}s 后重试" else "登录") }
            if (status.isNotEmpty()) BrowseNotice(status)
            Text("使用已注册的网易云账号登录", color = BrowseMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
        } else {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                qrContent?.let { QrCode(content = it, modifier = Modifier.size(200.dp)) } ?: Text(qrStatus, color = BrowseMuted)
            }
            Text(qrStatus, color = BrowseMuted)
            TextButton(onClick = { qrRefreshNonce++ }) { Text("刷新二维码") }
        }
    }
}

private enum class LoginMode { Phone, Qr }

private suspend fun runQrFlow(
    repository: PipoRepository,
    onContent: (String?) -> Unit,
    onStatus: (String) -> Unit,
) {
    onStatus("正在请求二维码…")
    val startResult = runCatching { repository.startQrLogin() }
    val start = startResult.getOrNull()
    if (start == null || start.qrContent.isBlank()) {
        onContent(null)
        onStatus("二维码加载失败，检查网络后刷新。")
        return
    }
    onContent(start.qrContent)
    onStatus("等待扫码。")
    repeat(30) {
        delay(2_000)
        val checkResult = runCatching { repository.checkQrLogin(start.key) }
        val s = checkResult.getOrNull()
        if (s == null) {
            onStatus("登录状态获取失败，稍等后刷新。")
            onContent(null)
            return
        }
        when (s.code) {
            801 -> onStatus("等待扫码。")
            802 -> onStatus("已扫码，在手机上确认登录。")
            803 -> {
                onStatus(s.nickname?.let { "已登录 · $it" } ?: "登录成功。")
                onContent(null)
                runCatching { repository.refreshAccount() }
                return
            }
            800 -> {
                onStatus("二维码已过期，点刷新重新生成。")
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
    onStatus("二维码超时，点刷新重新生成。")
    onContent(null)
}

private fun isPhoneLoginRestricted(code: Int?, message: String?): Boolean =
    code in setOf(-462, 503, 8821) || message?.let {
        it.contains("风险") || it.contains("风控") || it.contains("频繁") || it.contains("行为验证")
    } == true

private fun phoneStatus(sent: CaptchaSentStatus?, error: Throwable?): String {
    val msg = sent?.message?.takeIf { it.isNotBlank() }
    return when {
        error != null -> "请求失败：${error.message ?: error.javaClass.simpleName}"
        sent?.code == 200 -> "验证码已发送，请查收短信。"
        sent?.code == 8821 -> "网易云要求额外安全验证，请暂停重试，并在网易云官方客户端检查账号状态。"
        isPhoneLoginRestricted(sent?.code, msg) -> "${msg ?: "网易云暂时限制此请求"}。请稍后再试，勿连续发送验证码。"
        sent?.code != null -> msg ?: "发送失败，状态码 ${sent.code}。"
        else -> "发送失败，检查网络后重试。"
    }
}

private fun phoneLoginStatus(login: PhoneLoginStatus?, error: Throwable?): String {
    val msg = login?.message?.takeIf { it.isNotBlank() }
    return when {
        error != null -> "登录失败：${error.message ?: error.javaClass.simpleName}"
        login?.code == 200 -> login.nickname?.let { "登录成功 · $it" } ?: "登录成功。"
        login?.code == 8821 -> "网易云要求额外安全验证，请暂停重试，并在网易云官方客户端检查账号状态。"
        isPhoneLoginRestricted(login?.code, msg) -> "${msg ?: "网易云暂时限制此登录"}。请稍后再试，勿连续提交验证码。"
        login?.code == 502 -> msg ?: "验证码不对，重新输入。"
        login?.code != null -> msg ?: "登录失败，状态码 ${login.code}。"
        else -> "登录失败，检查网络后重试。"
    }
}
