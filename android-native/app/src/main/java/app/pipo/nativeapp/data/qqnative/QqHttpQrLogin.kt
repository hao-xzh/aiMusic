package app.pipo.nativeapp.data.qqnative

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.UUID

internal data class QqHttpQrResponse(
    val code: Int,
    val headers: Headers,
    val body: ByteArray,
)

/**
 * QQ 网页扫码的短生命周期 HTTP 会话。
 *
 * 凭据交换由调用方完成；本类只保留授权流程所需的内存 Cookie 与 OAuth code。
 */
internal class QqHttpQrLogin(
    baseClient: OkHttpClient,
    private val send: suspend (OkHttpClient, Request) -> QqHttpQrResponse,
) {
    private val cookies = SessionCookieJar()
    private val client = baseClient.newBuilder()
        .cookieJar(cookies)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile private var closed = false
    private val stateLock = Any()
    private var qrsig: String? = null
    private var callbackRedirect: String? = null

    suspend fun create(): String {
        ensureOpen()
        if (qrsig != null || callbackRedirect != null) {
            throw IllegalStateException("QQ 二维码会话已创建")
        }
        val request = Request.Builder()
            .url(
                PTQR_SHOW_URL.newBuilder()
                    .addQueryParameter("appid", APP_ID)
                    .addQueryParameter("e", "2")
                    .addQueryParameter("l", "M")
                    .addQueryParameter("s", "3")
                    .addQueryParameter("d", "72")
                    .addQueryParameter("v", "4")
                    .addQueryParameter("t", Math.random().toString())
                    .addQueryParameter("daid", DAID)
                    .addQueryParameter("pt_3rd_aid", CLIENT_ID)
                    .build(),
            )
            .header("Referer", PTLOGIN_REFERER)
            .get()
            .build()
        val response = execute(request)
        ensureOpen()
        requireSuccess(response, "获取 QQ 二维码失败")
        if (!response.body.startsWith(PNG_MAGIC)) {
            throw IllegalStateException("获取 QQ 二维码失败：响应不是 PNG 图片")
        }

        synchronized(stateLock) {
            ensureOpen()
            qrsig = cookies.valueFor(request.url, "qrsig")
                ?: throw IllegalStateException("获取 QQ 二维码失败：缺少 qrsig Cookie")
        }
        return "data:image/png;base64,${Base64.getEncoder().encodeToString(response.body)}"
    }

    suspend fun poll(): Int {
        ensureOpen()
        val currentQrsig = qrsig ?: throw IllegalStateException("QQ 二维码会话尚未创建")
        val request = Request.Builder()
            .url(
                PTQR_LOGIN_URL.newBuilder()
                    .addQueryParameter("u1", LOGIN_JUMP_URL)
                    .addQueryParameter("ptqrtoken", hash33(currentQrsig).toString())
                    .addQueryParameter("ptredirect", "0")
                    .addQueryParameter("h", "1")
                    .addQueryParameter("t", "1")
                    .addQueryParameter("g", "1")
                    .addQueryParameter("from_ui", "1")
                    .addQueryParameter("ptlang", "2052")
                    .addQueryParameter("action", "0-0-${System.currentTimeMillis()}")
                    .addQueryParameter("js_ver", "20102616")
                    .addQueryParameter("js_type", "1")
                    .addQueryParameter("pt_uistyle", "40")
                    .addQueryParameter("aid", APP_ID)
                    .addQueryParameter("daid", DAID)
                    .addQueryParameter("pt_3rd_aid", CLIENT_ID)
                    .addQueryParameter("has_onekey", "1")
                    .build(),
            )
            .header("Referer", PTLOGIN_REFERER)
            .get()
            .build()
        val response = execute(request)
        ensureOpen()
        requireSuccess(response, "查询 QQ 二维码状态失败")
        val fields = parsePtuiFields(response.body.toString(Charsets.UTF_8))
        val status = fields[0].toIntOrNull()
            ?: throw IllegalStateException("查询 QQ 二维码状态失败：状态码无效")

        when (status) {
            QR_WAITING, QR_SCANNED, QR_EXPIRED, QR_REJECTED -> return status
            QR_AUTHORIZED -> {
                val callback = fields[2]
                validateCallback(callback)
                synchronized(stateLock) {
                    ensureOpen()
                    callbackRedirect = callback
                }
                return status
            }
            else -> throw IllegalStateException("查询 QQ 二维码状态失败：未知协议状态 $status")
        }
    }

    suspend fun authorizationCode(): String {
        ensureOpen()
        val callback = callbackRedirect ?: throw IllegalStateException("QQ 二维码尚未完成授权")
        val callbackUrl = validateCallback(callback)
        val uin = callbackUrl.queryParameter("uin").orEmpty()
        val ptsigx = callbackUrl.queryParameter("ptsigx").orEmpty()
        if (uin.isBlank() || ptsigx.isBlank()) {
            throw IllegalStateException("QQ 授权回调缺少 uin 或 ptsigx")
        }

        val checkSigRequest = Request.Builder()
            .url(
                CHECK_SIG_URL.newBuilder()
                    .addQueryParameter("uin", uin)
                    .addQueryParameter("pttype", "1")
                    .addQueryParameter("service", "ptqrlogin")
                    .addQueryParameter("nodirect", "0")
                    .addQueryParameter("ptsigx", ptsigx)
                    .addQueryParameter("s_url", LOGIN_JUMP_URL)
                    .addQueryParameter("ptlang", "2052")
                    .addQueryParameter("ptredirect", "100")
                    .addQueryParameter("aid", APP_ID)
                    .addQueryParameter("daid", DAID)
                    .addQueryParameter("j_later", "0")
                    .addQueryParameter("low_login_hour", "0")
                    .addQueryParameter("regmaster", "0")
                    .addQueryParameter("pt_login_type", "3")
                    .addQueryParameter("pt_aid", "0")
                    .addQueryParameter("pt_aaid", "16")
                    .addQueryParameter("pt_light", "0")
                    .addQueryParameter("pt_3rd_aid", CLIENT_ID)
                    .build(),
            )
            .header("Referer", PTLOGIN_REFERER)
            .get()
            .build()
        val checkSigResponse = execute(checkSigRequest)
        ensureOpen()
        requireRedirectOrSuccess(checkSigResponse, "完成 QQ 授权失败")
        val pSkey = cookies.valueFor(checkSigRequest.url, "p_skey")
            ?: throw IllegalStateException("完成 QQ 授权失败：缺少 p_skey Cookie")

        val authorizationRequest = Request.Builder()
            .url(OAUTH_AUTHORIZE_URL)
            .post(
                FormBody.Builder()
                    .add("response_type", "code")
                    .add("client_id", CLIENT_ID)
                    .add("redirect_uri", OAUTH_REDIRECT_URI)
                    .add("scope", "get_user_info,get_app_friends")
                    .add("state", "state")
                    .add("switch", "")
                    .add("from_ptlogin", "1")
                    .add("src", "1")
                    .add("update_auth", "1")
                    .add("openapi", "1010_1030")
                    .add("g_tk", hash33(pSkey, 5381).toString())
                    .add("auth_time", ((System.currentTimeMillis() / 1000L) * 1000L).toString())
                    .add("ui", UUID.randomUUID().toString())
                    .build(),
            )
            .build()
        val authorizationResponse = execute(authorizationRequest)
        ensureOpen()
        requireRedirectOrSuccess(authorizationResponse, "获取 QQ OAuth 授权码失败")
        val location = authorizationResponse.headers["Location"]
            ?: throw IllegalStateException("获取 QQ OAuth 授权码失败：缺少回调地址")
        return extractOAuthCode(location)
    }

    fun close() = synchronized(stateLock) {
        closed = true
        cookies.clear()
        qrsig = null
        callbackRedirect = null
    }

    private suspend fun execute(request: Request): QqHttpQrResponse {
        ensureOpen()
        val response = send(client, request)
        ensureOpen()
        cookies.saveFromResponse(
            request.url,
            response.headers.values("Set-Cookie").mapNotNull { value -> Cookie.parse(request.url, value) },
        )
        ensureOpen()
        return response
    }

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("QQ 二维码会话已关闭")
    }

    private fun validateCallback(value: String): HttpUrl {
        if (value.any { it == '\\' || it == '\'' || it == '\"' || it == '\r' || it == '\n' }) {
            throw IllegalStateException("QQ 授权回调格式异常")
        }
        val callback = value.toHttpUrlOrNull()
            ?: throw IllegalStateException("QQ 授权回调地址无效")
        if (callback.scheme != "https" || callback.host != CHECK_SIG_HOST || callback.encodedPath != CHECK_SIG_PATH) {
            throw IllegalStateException("QQ 授权回调地址不受信任")
        }
        return callback
    }

    private fun extractOAuthCode(location: String): String {
        val redirect = location.toHttpUrlOrNull()
            ?: throw IllegalStateException("获取 QQ OAuth 授权码失败：回调地址无效")
        if (redirect.scheme != "https" || redirect.host != OAUTH_REDIRECT_HOST || redirect.encodedPath != OAUTH_REDIRECT_PATH) {
            throw IllegalStateException("获取 QQ OAuth 授权码失败：回调地址不受信任")
        }
        return redirect.queryParameter("code")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("获取 QQ OAuth 授权码失败：缺少 code")
    }

    private fun parsePtuiFields(body: String): List<String> {
        var index = body.indexOf(PTUI_CALLBACK_PREFIX)
        if (index < 0) throw IllegalStateException("查询 QQ 二维码状态失败：响应不是 ptuiCB")
        index += PTUI_CALLBACK_PREFIX.length
        val result = ArrayList<String>(3)
        repeat(3) { fieldIndex ->
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length || body[index] != '\'') {
                throw IllegalStateException("查询 QQ 二维码状态失败：ptuiCB 参数格式异常")
            }
            index++
            val value = StringBuilder()
            var escaped = false
            var hadEscape = false
            var closed = false
            while (index < body.length) {
                val current = body[index++]
                if (escaped) {
                    value.append(current)
                    escaped = false
                } else if (current == '\\') {
                    escaped = true
                    hadEscape = true
                } else if (current == '\'') {
                    closed = true
                    break
                } else {
                    value.append(current)
                }
            }
            if (!closed || escaped) throw IllegalStateException("查询 QQ 二维码状态失败：ptuiCB 参数未闭合")
            if (fieldIndex == 2 && (hadEscape || value.any { it == '\'' || it == '\"' || it == '\\' || it == '\r' || it == '\n' })) {
                throw IllegalStateException("查询 QQ 二维码状态失败：授权回调格式异常")
            }
            result += value.toString()
            while (index < body.length && body[index].isWhitespace()) index++
            if (fieldIndex < 2) {
                if (index >= body.length || body[index] != ',') {
                    throw IllegalStateException("查询 QQ 二维码状态失败：ptuiCB 参数缺失")
                }
                index++
            } else if (index >= body.length || (body[index] != ',' && body[index] != ')')) {
                throw IllegalStateException("查询 QQ 二维码状态失败：ptuiCB 参数格式异常")
            }
        }
        return result
    }

    private fun requireSuccess(response: QqHttpQrResponse, action: String) {
        if (response.code !in 200..299) throw IllegalStateException("$action：HTTP ${response.code}")
    }

    private fun requireRedirectOrSuccess(response: QqHttpQrResponse, action: String) {
        if (response.code !in 200..399) throw IllegalStateException("$action：HTTP ${response.code}")
    }

    private class SessionCookieJar : CookieJar {
        private val lock = Any()
        private val stored = ArrayList<Cookie>()
        private var accepting = true

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (!accepting) return
                stored.removeAll { it.expiresAt <= now }
                cookies.forEach { cookie ->
                    stored.removeAll {
                        it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                    }
                    if (cookie.expiresAt > now) stored += cookie
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
            if (!accepting) return@synchronized emptyList()
            val now = System.currentTimeMillis()
            stored.removeAll { it.expiresAt <= now }
            stored.filter { it.matches(url) }.sortedByDescending { it.path.length }
        }

        fun valueFor(url: HttpUrl, name: String): String? = synchronized(lock) {
            if (!accepting) return@synchronized null
            val now = System.currentTimeMillis()
            stored.removeAll { it.expiresAt <= now }
            stored.asSequence()
                .filter { it.name == name && it.matches(url) }
                .maxByOrNull { it.path.length }
                ?.value
        }

        fun clear() = synchronized(lock) {
            accepting = false
            stored.clear()
        }
    }

    private companion object {
        const val APP_ID = "716027609"
        const val CLIENT_ID = "100497308"
        const val DAID = "383"
        const val PTLOGIN_REFERER = "https://xui.ptlogin2.qq.com/"
        const val LOGIN_JUMP_URL = "https://graph.qq.com/oauth2.0/login_jump"
        const val OAUTH_REDIRECT_URI = "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/"
        const val CHECK_SIG_HOST = "ssl.ptlogin2.graph.qq.com"
        const val CHECK_SIG_PATH = "/check_sig"
        const val OAUTH_REDIRECT_HOST = "y.qq.com"
        const val OAUTH_REDIRECT_PATH = "/portal/wx_redirect.html"
        const val PTUI_CALLBACK_PREFIX = "ptuiCB("
        const val QR_AUTHORIZED = 0
        const val QR_EXPIRED = 65
        const val QR_WAITING = 66
        const val QR_SCANNED = 67
        const val QR_REJECTED = 68

        val PTQR_SHOW_URL = "https://ssl.ptlogin2.qq.com/ptqrshow".toHttpUrl()
        val PTQR_LOGIN_URL = "https://ssl.ptlogin2.qq.com/ptqrlogin".toHttpUrl()
        val CHECK_SIG_URL = "https://ssl.ptlogin2.graph.qq.com/check_sig".toHttpUrl()
        val OAUTH_AUTHORIZE_URL = "https://graph.qq.com/oauth2.0/authorize".toHttpUrl()
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}

private fun hash33(value: String, seed: Int = 0): Int {
    var hash = seed.toLong()
    value.forEach { character -> hash = ((hash * 33L) + character.code) and 0x7fff_ffffL }
    return hash.toInt()
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
