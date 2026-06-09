package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.CaptchaSentStatus
import app.pipo.nativeapp.data.PhoneLoginStatus
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoRepository
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
    var status by remember { mutableStateOf("输入手机号，先拿短信验证码。") }
    var sending by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var cooldown by remember { mutableIntStateOf(0) }

    var qrContent by remember { mutableStateOf<String?>(null) }
    var qrRefreshNonce by remember { mutableIntStateOf(0) }
    var qrStatus by remember { mutableStateOf("用网易云 App 扫码确认登录。") }

    val cleanPhone = phone.filter(Char::isDigit).take(11)
    val cleanCaptcha = captcha.filter(Char::isDigit).take(6)
    val phoneOk = Regex("^1\\d{10}$").matches(cleanPhone)
    val captchaOk = cleanCaptcha.length in 4..6

    LaunchedEffect(account) {
        if (account != null) onBack()
    }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1_000)
            cooldown -= 1
        }
    }

    LaunchedEffect(mode, qrRefreshNonce) {
        if (mode != LoginMode.Qr) {
            qrContent = null
            return@LaunchedEffect
        }
        runQrFlow(repository, onContent = { qrContent = it }, onStatus = { qrStatus = it })
    }

    ScreenScaffold(title = "LOGIN") {
        Text(
            "登录网易云，把歌单和云盘接进 Pipo。",
            color = PipoColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        LoginSectionHeader("01", "PHONE")
        LoginRow(title = "手机号", subtitle = "大陆手机号，默认 +86。") {
            LoginInput(
                value = cleanPhone,
                onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                placeholder = "11 位手机号",
                modifier = Modifier.widthIn(min = 156.dp, max = 210.dp),
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Next,
            )
        }
        LoginRow(title = "短信验证码", subtitle = "验证码通过后会自动写入本机 cookie。") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoginInput(
                    value = cleanCaptcha,
                    onValueChange = { captcha = it.filter(Char::isDigit).take(6) },
                    placeholder = "验证码",
                    modifier = Modifier.width(104.dp),
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                )
                LoginButton(
                    text = when {
                        sending -> "发送中"
                        cooldown > 0 -> "${cooldown}s"
                        else -> "发送"
                    },
                    enabled = phoneOk && !sending && cooldown == 0,
                    onClick = {
                        scope.launch {
                            sending = true
                            status = "正在请求验证码…"
                            val result = runCatching { repository.sendPhoneCaptcha(cleanPhone) }
                            val sent = result.getOrNull()
                            if (sent?.code == 200) {
                                cooldown = 60
                                status = "验证码已发送。"
                                DiagnosticsLogStore.record("login", "phone_captcha_sent")
                            } else {
                                status = phoneStatus(sent, result.exceptionOrNull())
                                DiagnosticsLogStore.record(
                                    area = "login",
                                    event = "phone_captcha_failed",
                                    fields = mapOf(
                                        "code" to sent?.code,
                                        "message" to sent?.message.orEmpty().take(120),
                                        "errorType" to result.exceptionOrNull()?.javaClass?.simpleName.orEmpty(),
                                    ),
                                )
                            }
                            sending = false
                        }
                    },
                )
            }
        }
        LoginRow(title = "登录状态", subtitle = status, showDivider = false) {
            LoginButton(
                text = if (loggingIn) "登录中" else "登录",
                isPrimary = true,
                enabled = phoneOk && captchaOk && !loggingIn,
                onClick = {
                    scope.launch {
                        loggingIn = true
                        status = "正在校验验证码并登录…"
                        val result = runCatching { repository.loginWithPhone(cleanPhone, cleanCaptcha) }
                        val login = result.getOrNull()
                        if (login?.code == 200) {
                            DiagnosticsLogStore.record(
                                area = "login",
                                event = "phone_login_success",
                                fields = mapOf("hasNickname" to !login.nickname.isNullOrBlank()),
                            )
                            status = login.nickname?.let { "已登录 · $it" } ?: "登录成功"
                            runCatching { repository.refreshAccount() }
                        } else {
                            status = phoneLoginStatus(login, result.exceptionOrNull())
                            DiagnosticsLogStore.record(
                                area = "login",
                                event = "phone_login_failed",
                                fields = mapOf(
                                    "code" to login?.code,
                                    "message" to login?.message.orEmpty().take(120),
                                    "errorType" to result.exceptionOrNull()?.javaClass?.simpleName.orEmpty(),
                                ),
                            )
                        }
                        loggingIn = false
                    }
                },
            )
        }

        LoginSectionHeader("02", "FALLBACK")
        LoginRow(
            title = "扫码登录",
            subtitle = "如果网易云提示风险，扫码通常更稳。",
            showDivider = mode != LoginMode.Qr,
        ) {
            LoginButton(
                text = if (mode == LoginMode.Qr && qrContent != null) "刷新二维码" else "打开扫码",
                isPrimary = mode == LoginMode.Qr,
                onClick = {
                    mode = LoginMode.Qr
                    qrRefreshNonce += 1
                },
            )
        }

        if (mode == LoginMode.Qr) {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .border(1.dp, PipoColors.GlassStroke, RectangleShape)
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                qrContent?.let { content ->
                    QrCode(content = content, modifier = Modifier.size(184.dp))
                } ?: Box(modifier = Modifier.size(184.dp), contentAlignment = Alignment.Center) {
                    Text(qrStatus, color = PipoColors.TextDim, style = TextStyle(fontSize = 12.sp))
                }
            }
            LoginRow(title = "扫码状态", subtitle = qrStatus, showDivider = false)
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

private fun phoneStatus(sent: CaptchaSentStatus?, error: Throwable?): String {
    val msg = sent?.message?.takeIf { it.isNotBlank() }
    return when {
        error != null -> "请求失败：${error.message ?: error.javaClass.simpleName}"
        msg?.contains("风险") == true -> "网易云提示风险，等一会儿再试，或改用扫码登录。"
        sent?.code == 503 -> msg ?: "发送太频繁，稍后再试。"
        sent?.code != null -> msg ?: "发送失败，状态码 ${sent.code}。"
        else -> "发送失败，检查网络后重试。"
    }
}

private fun phoneLoginStatus(login: PhoneLoginStatus?, error: Throwable?): String {
    val msg = login?.message?.takeIf { it.isNotBlank() }
    return when {
        error != null -> "登录失败：${error.message ?: error.javaClass.simpleName}"
        msg?.contains("风险") == true -> "网易云提示风险，改用扫码登录更稳。"
        login?.code == 502 -> msg ?: "验证码不对，重新输入。"
        login?.code != null -> msg ?: "登录失败，状态码 ${login.code}。"
        else -> "登录失败，检查网络后重试。"
    }
}

@Composable
private fun LoginSectionHeader(index: String, title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = index,
                color = PipoColors.TextDim,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
            )
            Text(
                text = "/",
                color = PipoColors.TextDim.copy(alpha = 0.5f),
                style = TextStyle(fontSize = 11.sp),
            )
            Text(
                text = title.uppercase(),
                color = PipoColors.Ink,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LoginDivider()
    }
}

@Composable
private fun LoginRow(
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    content: @Composable (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = PipoColors.Ink,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                subtitle?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = it,
                        color = PipoColors.TextDim,
                        style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                    )
                }
            }
            content?.let {
                Spacer(modifier = Modifier.width(16.dp))
                it()
            }
        }
        if (showDivider) LoginDivider()
    }
}

@Composable
private fun LoginDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PipoColors.GlassStroke),
    )
}

@Composable
private fun LoginButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    enabled: Boolean = true,
) {
    val backgroundColor = if (isPrimary && enabled) PipoColors.Ink else Color.Transparent
    val textColor = when {
        !enabled -> PipoColors.TextDim.copy(alpha = 0.55f)
        isPrimary -> PipoColors.Bg0
        else -> PipoColors.Ink
    }
    val borderColor = if (enabled) PipoColors.GlassStroke else PipoColors.GlassStroke.copy(alpha = 0.45f)
    val borderModifier = if (isPrimary && enabled) Modifier else Modifier.border(1.dp, borderColor, RectangleShape)

    Box(
        modifier = modifier
            .background(backgroundColor, RectangleShape)
            .then(borderModifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
        )
    }
}

@Composable
private fun LoginInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .border(1.dp, PipoColors.GlassStroke, RectangleShape)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isBlank()) {
            Text(
                text = placeholder,
                color = PipoColors.TextDim.copy(alpha = 0.72f),
                style = TextStyle(fontSize = 12.sp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(PipoColors.Mint),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            textStyle = TextStyle(color = PipoColors.Ink, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
