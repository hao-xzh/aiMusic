package app.pipo.nativeapp.data.qqnative

import android.content.Context
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.StabilityDiagnostics
import app.pipo.nativeapp.data.MusicProviderAccount
import app.pipo.nativeapp.data.MusicProviderConnection
import app.pipo.nativeapp.data.MusicProviderError
import app.pipo.nativeapp.data.MusicProviderException
import app.pipo.nativeapp.data.PipoHttp
import app.pipo.nativeapp.data.QrLoginStart
import app.pipo.nativeapp.data.QrLoginStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec

/**
 * Android 本地 QQ Music 协议传输。网络调用直接到 QQ Music / WeChat，凭据只保存在
 * [QqCredentialStore] 的 AndroidKeyStore AES-GCM 密文中，不经过桌面端或外部 API 服务。
 */
class QqNativeTransport(context: Context) {
    private val appContext = context.applicationContext
    private val store = QqCredentialStore(context)
    private val http = PipoHttp.client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceLock = Mutex()
    private val credentialLock = Mutex()
    private val sessionGeneration = AtomicLong(0)
    private val inFlightCalls = ConcurrentHashMap<Call, Long>()
    private val sessions = ConcurrentHashMap<String, QrSession>()
    private val _connection = MutableStateFlow(MusicProviderConnection(configured = true))

    val connection: StateFlow<MusicProviderConnection> = _connection.asStateFlow()
    val loginMethods: List<String> = listOf(CHANNEL_QQ, CHANNEL_WECHAT)

    /** 仅暴露当前用户的非敏感标识；musickey 永不从该 transport 暴露。 */
    val accountId: String? get() = credential()?.accountId
    val encryptUin: String? get() = credential()?.encryptUin
    val loginType: Int? get() = credential()?.loginType

    suspend fun refreshAccount() {
        val capturedGeneration = sessionGeneration.get()
        var credential = credential() ?: run {
            credentialLock.withLock {
                if (capturedGeneration == sessionGeneration.get()) {
                    _connection.value = MusicProviderConnection(configured = true)
                }
            }
            return
        }
        var profile: JSONObject
        try {
            profile = loginProfile(credential, capturedGeneration)
            val account = accountFrom(profile, credential).copy(
                hasMusicSubscription = musicSubscription(credential, capturedGeneration),
            )
            credentialLock.withLock {
                if (capturedGeneration != sessionGeneration.get()) return
                _connection.value = MusicProviderConnection(configured = true, account = account)
            }
        } catch (failure: MusicProviderException) {
            if (failure.code == MusicProviderError.STALE_SESSION && credential.loginType in setOf(WECHAT_LOGIN_TYPE, QQ_WEB_LOGIN_TYPE)) {
                credential = refreshCredential(credential, capturedGeneration)
                profile = loginProfile(credential, capturedGeneration)
                val account = accountFrom(profile, credential).copy(
                    hasMusicSubscription = musicSubscription(credential, capturedGeneration),
                )
                credentialLock.withLock {
                    if (capturedGeneration != sessionGeneration.get()) return
                    store.saveCredential(credential.values)
                    _connection.value = MusicProviderConnection(
                        configured = true,
                        account = account,
                    )
                }
                return
            }
            credentialLock.withLock {
                if (capturedGeneration == sessionGeneration.get()) {
                    _connection.value = MusicProviderConnection(configured = true, error = failure.message)
                }
            }
            throw failure
        }
    }

    suspend fun startQr(channel: String): QrLoginStart {
        val normalized = channel.lowercase(Locale.US)
        require(normalized in loginMethods) { "Unsupported QQ login channel: $channel" }
        removeExpiredSessions()
        val generation = sessionGeneration.get()
        // QIMEI 与 Android session 必须和后续 exchange/call 使用同一个持久 device context。
        ensureDeviceSession(forceSessionRefresh = true, expectedGeneration = generation)
        val key = UUID.randomUUID().toString()
        val session = when (normalized) {
            CHANNEL_WECHAT -> startWechat(key, generation)
            CHANNEL_QQ -> startQq(key, generation)
            else -> error("unreachable")
        }
        assertGeneration(generation)
        sessions[key] = session
        DiagnosticsLogStore.record("qq_login", "qr_ready", mapOf("channel" to normalized))
        return QrLoginStart(key = key, qrContent = session.content)
    }

    suspend fun checkQr(key: String): QrLoginStatus {
        val session = sessions[key] ?: return QrLoginStatus(code = QR_EXPIRED, message = "二维码不存在或已过期")
        if (session.isExpired()) {
            DiagnosticsLogStore.record("qq_login", "qr_expired", mapOf("channel" to session.channel, "state" to session.state.name))
            cancelQr(key)
            return QrLoginStatus(code = QR_EXPIRED, message = "二维码已过期")
        }
        if (session.channel == CHANNEL_WECHAT) advanceWechat(session) else advanceQq(session)
        return when (session.state) {
            QrState.WAITING -> QrLoginStatus(QR_WAITING, session.message ?: "等待扫码")
            QrState.SCANNED -> QrLoginStatus(QR_SCANNED, session.message ?: "已扫码，请在手机确认")
            QrState.EXCHANGING -> QrLoginStatus(QR_SCANNED, "已授权，正在连接 QQ 音乐…")
            QrState.AUTHORIZED -> finalizeAuthorized(session)
            QrState.CONFIRMED -> QrLoginStatus(QR_CONFIRMED, "登录成功", session.nickname)
            QrState.EXPIRED -> QrLoginStatus(QR_EXPIRED, session.message ?: "二维码已过期")
            QrState.FAILED -> QrLoginStatus(QR_EXPIRED, session.message ?: "登录失败")
        }
    }

    suspend fun cancelQr(key: String) {
        sessions.remove(key)?.close()
    }

    suspend fun logout() {
        val (oldGeneration, current) = credentialLock.withLock {
            val generation = sessionGeneration.incrementAndGet()
            inFlightCalls.entries.forEach { (call, requestGeneration) ->
                if (requestGeneration < generation) call.cancel()
            }
            sessions.values.forEach { it.close() }
            sessions.clear()
            val saved = credential()
            store.clearCredential()
            _connection.value = MusicProviderConnection(configured = true)
            generation to saved
        }
        if (current == null) return
        try {
            callMusicu(
                module = "music.login.LoginServer",
                method = "Logout",
                param = JSONObject(),
                credential = current,
                expectedGeneration = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: MusicProviderException) {
            // 本地凭据已在 generation 递增后删除；远端失败仍要如实交给调用方。
            if (oldGeneration == sessionGeneration.get()) throw e
        }
    }

    /**
     * 统一 musicu RPC。成功时只返回 `req_0.data`；上游全局码和 req_0.code 都会被验证。
     */
    suspend fun call(
        module: String,
        method: String,
        param: JSONObject,
        authenticated: Boolean = true,
    ): JSONObject {
        require(module.isNotBlank() && method.isNotBlank()) { "musicu module and method are required" }
        val expectedGeneration = if (authenticated) sessionGeneration.get() else null
        val credential = if (authenticated) {
            credential() ?: throw providerError(MusicProviderError.AUTH_REQUIRED, "QQ Music 尚未登录")
        } else {
            null
        }
        return callMusicu(
            module,
            method,
            JSONObject(param.toString()),
            credential,
            expectedGeneration = expectedGeneration,
        )
    }

    private suspend fun startWechat(key: String, generation: Long): QrSession {
        val page = requestText(
            url = WECHAT_CONNECT_URL,
            query = mapOf(
                "appid" to WECHAT_APP_ID,
                "redirect_uri" to WECHAT_REDIRECT_URI,
                "response_type" to "code",
                "scope" to "snsapi_login",
                "state" to "STATE",
                "href" to WECHAT_STYLE_HREF,
            ),
            expectedGeneration = generation,
        )
        val identifier = UUID_PATTERN.find(page)?.groupValues?.getOrNull(1).orEmpty()
        if (identifier.isBlank()) throw providerError(MusicProviderError.REMOTE, "微信二维码响应缺少 uuid")
        val image = requestBytes(
            "$WECHAT_IMAGE_URL$identifier",
            headers = mapOf("Referer" to WECHAT_CONNECT_URL),
            expectedGeneration = generation,
        )
        val mime = when {
            image.startsWith(PNG_MAGIC) -> "image/png"
            image.startsWith(JPEG_MAGIC) -> "image/jpeg"
            else -> throw providerError(MusicProviderError.REMOTE, "微信二维码不是有效图片")
        }
        return QrSession(
            key = key,
            channel = CHANNEL_WECHAT,
            generation = generation,
            identifier = identifier,
            content = "data:$mime;base64,${Base64.getEncoder().encodeToString(image)}",
        )
    }

    private suspend fun startQq(key: String, generation: Long): QrSession {
        val login = QqHttpQrLogin(http) { client, request ->
            execute(client, request, QQ_LOGIN_REQUEST_TIMEOUT_MS, generation) { response ->
                QqHttpQrResponse(response.code, response.headers, response.body?.bytes() ?: ByteArray(0))
            }
        }
        try {
            return QrSession(
                key = key,
                channel = CHANNEL_QQ,
                generation = generation,
                identifier = key,
                content = login.create(),
                qqHttp = login,
            )
        } catch (failure: Exception) {
            login.close()
            throw failure
        }
    }

    private suspend fun advanceQq(session: QrSession) {
        if (session.state !in setOf(QrState.WAITING, QrState.SCANNED)) return
        val login = session.qqHttp ?: throw providerError(MusicProviderError.REMOTE, "QQ 扫码会话已失效，请刷新二维码")
        val status = try {
            login.poll()
        } catch (failure: MusicProviderException) {
            if (failure.code != MusicProviderError.NETWORK) throw failure
            // A short-lived poll can resume after returning from QQ without losing the QR session.
            if (session.message == null) DiagnosticsLogStore.record("qq_login", "http_poll_interrupted")
            session.message = "等待网络恢复，返回 PIPO 后会继续确认登录…"
            return
        }
        if (session.message != null) DiagnosticsLogStore.record("qq_login", "http_poll_resumed")
        session.message = null
        val next = when (status) {
            66 -> QrState.WAITING
            67 -> QrState.SCANNED
            0 -> QrState.AUTHORIZED
            65, 68 -> QrState.EXPIRED
            else -> throw providerError(MusicProviderError.REMOTE, "QQ 返回未知扫码状态")
        }
        if (next != session.state) DiagnosticsLogStore.record("qq_login", "http_qr_state", mapOf("code" to status, "state" to next.name))
        session.state = next
        if (status == 68) session.message = "已取消 QQ 登录，请刷新二维码后重试。"
        if (status == 65) session.message = "QQ 二维码已过期，请刷新二维码。"
    }

    private suspend fun exchangeQq(session: QrSession): QqCredential {
        val login = session.qqHttp ?: throw providerError(MusicProviderError.REMOTE, "QQ 扫码会话已失效")
        DiagnosticsLogStore.record("qq_login", "oauth_exchange_started")
        val code = login.authorizationCode()
        DiagnosticsLogStore.record("qq_login", "oauth_code_ready")
        return credentialFrom(
            callMusicu(
                module = "QQConnectLogin.LoginServer",
                method = "QQLogin",
                param = JSONObject().put("code", code),
                credential = null,
                commOverrides = JSONObject().put("tmeLoginType", QQ_WEB_LOGIN_TYPE),
                expectedGeneration = session.generation,
            ),
            QQ_WEB_LOGIN_TYPE,
        )
    }

    private suspend fun advanceWechat(session: QrSession) {
        if (session.state !in setOf(QrState.WAITING, QrState.SCANNED)) return
        val response = requestText(
            url = WECHAT_POLL_URL,
            query = mapOf("uuid" to session.identifier, "_" to System.currentTimeMillis().toString()),
            headers = mapOf("Referer" to "https://open.weixin.qq.com/"),
            timeoutMs = WECHAT_POLL_TIMEOUT_MS,
            expectedGeneration = session.generation,
        )
        val match = WECHAT_STATUS_PATTERN.find(response)
            ?: throw providerError(MusicProviderError.REMOTE, "微信登录状态响应无效")
        when (match.groupValues[1].toIntOrNull()) {
            408 -> session.state = QrState.WAITING
            404 -> session.state = QrState.SCANNED
            405 -> {
                session.authorization = JSONObject().put("code", match.groupValues[2])
                session.state = QrState.AUTHORIZED
            }
            402, 403 -> {
                session.message = "微信二维码已过期或被取消"
                session.state = QrState.EXPIRED
            }
            else -> throw providerError(MusicProviderError.REMOTE, "微信返回未知二维码状态 ${match.groupValues[1]}")
        }
    }

    private fun finalizeAuthorized(session: QrSession): QrLoginStatus {
        synchronized(session) {
            if (session.exchange == null) {
                session.state = QrState.EXCHANGING
                session.exchange = scope.async { completeAuthorized(session) }
            }
        }
        // Keep polling responsive while the account exchange runs in the transport scope.
        return QrLoginStatus(QR_SCANNED, "已授权，正在连接 QQ 音乐…")
    }

    private suspend fun completeAuthorized(session: QrSession): QrLoginStatus {
        return try {
            DiagnosticsLogStore.record("qq_login", "exchange_started", mapOf("channel" to session.channel))
            val credential = if (session.channel == CHANNEL_WECHAT) {
                exchangeWechat(session.authorization ?: JSONObject(), session.generation)
            } else {
                exchangeQq(session)
            }
            DiagnosticsLogStore.record("qq_login", "exchange_ready", mapOf("channel" to session.channel))
            val profile = loginProfile(credential, session.generation)
            DiagnosticsLogStore.record("qq_login", "profile_ready", mapOf("channel" to session.channel))
            val account = accountFrom(profile, credential).copy(
                hasMusicSubscription = musicSubscription(credential, session.generation),
            )
            credentialLock.withLock {
                if (session.generation != sessionGeneration.get() || sessions[session.key] !== session) {
                    throw providerError(MusicProviderError.STALE_SESSION, "登录结果已失效")
                }
                store.saveCredential(credential.values)
                _connection.value = MusicProviderConnection(configured = true, account = account)
                session.nickname = account.nickname
                session.state = QrState.CONFIRMED
            }
            DiagnosticsLogStore.record("qq_login", "account_connected", mapOf("channel" to session.channel))
            QrLoginStatus(QR_CONFIRMED, "登录成功", account.nickname)
        } catch (e: TimeoutCancellationException) {
            DiagnosticsLogStore.record("qq_login", "exchange_failed", mapOf("error_type" to "timeout"))
            session.message = "连接 QQ 音乐超时，请刷新二维码后重试。"
            session.state = QrState.FAILED
            QrLoginStatus(QR_EXPIRED, session.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MusicProviderException) {
            DiagnosticsLogStore.record("qq_login", "exchange_failed", mapOf("error_code" to e.code.name))
            session.message = e.message
            session.state = QrState.FAILED
            QrLoginStatus(QR_EXPIRED, e.message)
        } catch (e: Exception) {
            DiagnosticsLogStore.record("qq_login", "exchange_failed", mapOf("error_type" to e.javaClass.simpleName))
            session.message = "登录交换失败"
            session.state = QrState.FAILED
            QrLoginStatus(QR_EXPIRED, session.message)
        } finally {
            session.qqHttp?.close()
        }
    }

    private suspend fun exchangeWechat(event: JSONObject, expectedGeneration: Long): QqCredential {
        val code = event.optString("code")
        if (code.isBlank()) throw providerError(MusicProviderError.REMOTE, "微信授权未返回 code")
        return credentialFrom(
            callMusicu(
                module = "music.login.LoginServer",
                method = "Login",
                param = JSONObject().put("code", code).put("strAppid", WECHAT_APP_ID),
                credential = null,
                commOverrides = JSONObject().put("tmeLoginType", WECHAT_LOGIN_TYPE),
                expectedGeneration = expectedGeneration,
            ),
            WECHAT_LOGIN_TYPE,
        )
    }

    private suspend fun refreshCredential(old: QqCredential, expectedGeneration: Long): QqCredential {
        val values = old.values
        val param = if (old.loginType == QQ_WEB_LOGIN_TYPE) JSONObject()
            .put("openid", values.optString("openid"))
            .put("access_token", values.optString("access_token"))
            .put("refresh_token", values.optString("refresh_token"))
            .put("expired_in", values.optLong("expired_at"))
            .put("musicid", values.optLong("musicid", old.accountId.toLongOrNull() ?: 0L))
            .put("musickey", old.musickey)
            .put("refresh_key", values.optString("refresh_key"))
            .put("loginMode", 2)
        else JSONObject()
            .put("openid", values.optString("openid"))
            .put("refresh_token", values.optString("refresh_token"))
            .put("str_musicid", values.optString("str_musicid").ifBlank { old.accountId })
            .put("musickey", old.musickey)
            .put("unionid", values.optString("unionid"))
            .put("refresh_key", values.optString("refresh_key"))
            .put("loginMode", 2)
        return credentialFrom(
            callMusicu(
                "music.login.LoginServer",
                "Login",
                param,
                old,
                JSONObject().put("tmeLoginType", old.loginType),
                expectedGeneration,
            ),
            old.loginType,
        )
    }

    private suspend fun loginProfile(credential: QqCredential, expectedGeneration: Long): JSONObject = callMusicu(
        module = "music.UserInfo.userInfoServer",
        method = "GetLoginUserInfo",
        param = JSONObject(),
        credential = credential,
        expectedGeneration = expectedGeneration,
    )

    private suspend fun musicSubscription(credential: QqCredential, expectedGeneration: Long): Boolean? =
        withTimeoutOrNull(3_000L) {
            try {
                // QQMusicApi 2fba197: user.get_vip_info and UserVipInfoResponse/VipIdentity.
                val data = callMusicu(
                    module = "VipLogin.VipLoginInter",
                    method = "vip_login_base",
                    param = JSONObject(),
                    credential = credential,
                    expectedGeneration = expectedGeneration,
                )
                fun flag(value: Any?): Int? = when (value) {
                    is Number -> value.toInt().takeIf { it >= 0 }
                    is String -> value.toIntOrNull()?.takeIf { it >= 0 }
                    else -> null
                }
                val identity = data.optJSONObject("identity")
                val flags = listOf(flag(data.opt("svip")), flag(identity?.opt("vip")), flag(identity?.opt("HugeVip")))
                when {
                    flags.any { it != null && it > 0 } -> true
                    flags.all { it == 0 } -> false
                    else -> null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Membership failure must not turn a valid login into a logged-out account.
                null
            }
        }

    private suspend fun callMusicu(
        module: String,
        method: String,
        param: JSONObject,
        credential: QqCredential?,
        commOverrides: JSONObject = JSONObject(),
        expectedGeneration: Long? = null,
    ): JSONObject {
        val libraryRead = method in setOf("GetPlaylistByUin", "CgiGetPlaylistFavInfo", "CgiGetDiss")
        if (libraryRead) DiagnosticsLogStore.record("qq_library", "request_started", mapOf("method" to method))
        val device = ensureDeviceSession(expectedGeneration = expectedGeneration)
        val comm = buildComm(device, credential, commOverrides)
        val body = JSONObject()
            .put("comm", comm)
            .put("req_0", JSONObject().put("module", module).put("method", method).put("param", param))
        val headers = mutableMapOf("User-Agent" to "QQMusic $APP_VERSION(android ${device.osRelease})")
        credential?.let { headers["Cookie"] = credential.cookieHeader() }
        val response = requestJson(MUSICU_URL, body, headers, expectedGeneration)
        val globalCode = response.optInt("code", 0)
        val item = response.optJSONObject("req_0")
            ?: throw providerError(MusicProviderError.REMOTE, "musicu 响应缺少 req_0")
        val itemCode = item.optInt("code", 0)
        if (libraryRead) DiagnosticsLogStore.record("qq_library", "request_finished", mapOf("method" to method, "code" to globalCode, "item_code" to itemCode))
        if (module == "music.login.LoginServer" || module == "QQConnectLogin.LoginServer" || method == "GetLoginUserInfo") {
            DiagnosticsLogStore.record("qq_login", "api_result", mapOf("method" to method, "code" to globalCode, "item_code" to itemCode))
        }
        if (globalCode != 0 || itemCode != 0) {
            throw upstreamError(itemCode.takeIf { it != 0 } ?: globalCode)
        }
        return item.optJSONObject("data") ?: JSONObject()
    }

    private suspend fun ensureDeviceSession(
        forceSessionRefresh: Boolean = false,
        expectedGeneration: Long? = null,
    ): QqDeviceContext = deviceLock.withLock {
        var device = store.loadDevice() ?: QqDeviceContext.create().also(store::saveDevice)
        val now = System.currentTimeMillis()
        val qimeiFresh = !device.qimei.isNullOrBlank() && !device.qimei36.isNullOrBlank() &&
            (device.qimeiSavedAt ?: 0L) + QIMEI_TTL_MS > now
        if (!qimeiFresh) {
            val request = buildQimeiRequest(device)
            val reply = requestJson(QIMEI_URL, request.body, request.headers, expectedGeneration)
            val outerCode = reply.optInt("code", 0)
            val inner = reply.objectValue("data")
            val innerCode = inner.optInt("code", 0)
            val data = inner.objectValue("data")
            val q16 = data.optString("q16")
            val q36 = data.optString("q36")
            if (outerCode != 0 || innerCode != 0 || q16.isBlank() || q36.isBlank()) {
                throw providerError(MusicProviderError.REMOTE, "QIMEI 初始化被上游拒绝 (outer=$outerCode, inner=$innerCode)")
            }
            device = device.withQimei(q16, q36, now)
            store.saveDevice(device)
        }
        val sessionFresh = !device.sessionUid.isNullOrBlank() && !device.sessionSid.isNullOrBlank() &&
            (device.sessionSavedAt ?: 0L) + ANDROID_SESSION_TTL_MS > now
        if (forceSessionRefresh || !sessionFresh) {
            val data = callMusicuWithoutBootstrap(
                device = device,
                module = "music.getSession.session",
                method = "GetSession",
                param = JSONObject().put("uid", device.sessionUid.orEmpty()).put("vkey", 0).put("caller", 0),
                expectedGeneration = expectedGeneration,
            )
            val session = data.optJSONObject("session") ?: JSONObject()
            val uid = session.optString("uid")
            val sid = session.optString("sid")
            if (uid.isBlank() || sid.isBlank()) throw providerError(MusicProviderError.REMOTE, "Android session 初始化缺少 uid/sid")
            device = device.withSession(uid, sid, session.optString("vkey").takeIf { it.isNotBlank() }, now)
            store.saveDevice(device)
        }
        device
    }

    private suspend fun callMusicuWithoutBootstrap(
        device: QqDeviceContext,
        module: String,
        method: String,
        param: JSONObject,
        expectedGeneration: Long?,
    ): JSONObject {
        val body = JSONObject()
            .put("comm", buildComm(device, null, JSONObject()))
            .put("req_0", JSONObject().put("module", module).put("method", method).put("param", param))
        val response = requestJson(
            MUSICU_URL,
            body,
            mapOf("User-Agent" to "QQMusic $APP_VERSION(android ${device.osRelease})"),
            expectedGeneration,
        )
        val item = response.optJSONObject("req_0") ?: throw providerError(MusicProviderError.REMOTE, "musicu 响应缺少 req_0")
        val globalCode = response.optInt("code", 0)
        val itemCode = item.optInt("code", 0)
        if (globalCode != 0 || itemCode != 0) throw upstreamError(itemCode.takeIf { it != 0 } ?: globalCode)
        return item.optJSONObject("data") ?: JSONObject()
    }

    private fun buildComm(device: QqDeviceContext, credential: QqCredential?, overrides: JSONObject): JSONObject = JSONObject().apply {
        put("ct", 11)
        put("cv", APP_VERSION)
        put("v", APP_VERSION)
        put("chid", CHANNEL_ID)
        put("tmeAppID", "qqmusic")
        put("QIMEI", device.qimei.orEmpty())
        put("QIMEI36", device.qimei36.orEmpty())
        put("OpenUDID", device.openUdid)
        put("OpenUDID2", device.openUdid)
        put("udid", device.openUdid)
        put("aid", device.androidId)
        put("os_ver", device.osRelease)
        put("phonetype", device.model)
        put("devicelevel", device.sdk.toString())
        put("newdevicelevel", device.sdk.toString())
        put("rom", device.fingerprint)
        device.sessionUid?.let { put("uid", it) }
        device.sessionSid?.let { put("sid", it) }
        credential?.let {
            put("qq", it.accountId)
            put("authst", it.musickey)
            put("tmeLoginType", it.loginType)
        }
        overrides.keys().forEach { name -> put(name, overrides.get(name)) }
    }

    private fun buildQimeiRequest(device: QqDeviceContext): QimeiRequest {
        val random = SecureRandom()
        fun hex(size: Int) = ByteArray(size).also(random::nextBytes)
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        val nowSeconds = System.currentTimeMillis() / 1000L
        val key = hex(8).padEnd(16, '0')
        val nonce = hex(8).padEnd(16, '0')
        val payload = qimeiPayload(device, nowSeconds)
        val encryptedKey = rsaEncrypt(key.toByteArray(StandardCharsets.UTF_8))
        val encryptedPayload = aesCbcEncrypt(key.toByteArray(StandardCharsets.UTF_8), payload.toString().toByteArray(StandardCharsets.UTF_8))
        val keyText = Base64.getEncoder().encodeToString(encryptedKey)
        val params = Base64.getEncoder().encodeToString(encryptedPayload)
        val extra = "{\"appKey\":\"$QIMEI_APP_KEY\"}"
        val body = JSONObject().put("app", 0).put("os", 1).put("qimeiParams", JSONObject()
            .put("key", keyText)
            .put("params", params)
            .put("time", nowSeconds.toString())
            .put("nonce", nonce)
            .put("sign", md5(keyText, params, (nowSeconds * 1000L).toString(), nonce, QIMEI_SECRET, extra))
            .put("extra", extra))
        return QimeiRequest(
            body = body,
            headers = mapOf(
                "Host" to "api.tencentmusic.com",
                "method" to "GetQimei",
                "service" to "trpc.tme_datasvr.qimeiproxy.QimeiProxy",
                "appid" to "qimei_qq_android",
                "sign" to md5("qimei_qq_androidpzAuCmaFAaFaHrdakPjLIEqKrGnSOOvH", nowSeconds.toString()),
                "user-agent" to "QQMusic",
                "timestamp" to nowSeconds.toString(),
            ),
        )
    }

    private fun qimeiPayload(device: QqDeviceContext, nowSeconds: Long): JSONObject {
        val reserved = JSONObject()
            .put("harmony", "0")
            .put("clone", "0")
            .put("containe", "")
            .put("oz", "UhYmelwouA+V2nPWbOvLTgN2/m8jwGB+yUB5v9tysQg=")
            .put("oo", "Xecjt+9S1+f8Pz2VLSxgpw==")
            .put("kelong", "0")
            .put("uptimes", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date((nowSeconds - SecureRandom().nextInt(14_401)) * 1000L)))
            .put("multiUser", "0")
            .put("bod", device.brand)
            .put("dv", device.device)
            .put("firstLevel", "")
            .put("manufact", device.brand)
            .put("name", device.model)
            .put("host", "se.infra")
            .put("kernel", device.procVersion)
        return JSONObject()
            .put("androidId", device.androidId)
            .put("platformId", 1)
            .put("appKey", QIMEI_APP_KEY)
            .put("appVersion", "14.9.0.8")
            .put("beaconIdSrc", randomBeaconId())
            .put("brand", device.brand)
            .put("channelId", CHANNEL_ID)
            .put("cid", "")
            .put("imei", device.imei)
            .put("imsi", "")
            .put("mac", "")
            .put("model", device.model)
            .put("networkType", "unknown")
            .put("oaid", "")
            .put("osVersion", "Android ${device.osRelease},level ${device.sdk}")
            .put("qimei", "")
            .put("qimei36", "")
            .put("sdkVersion", "1.2.13.6")
            .put("targetSdkVersion", "33")
            .put("audit", "")
            .put("userId", "{}")
            .put("packageId", "com.tencent.qqmusic")
            .put("deviceType", "Phone")
            .put("sdkName", "")
            .put("reserved", reserved.toString())
    }

    private suspend fun requestJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        expectedGeneration: Long? = null,
    ): JSONObject {
        val text = requestText(url, body, headers, expectedGeneration = expectedGeneration)
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            throw providerError(MusicProviderError.REMOTE, "上游返回不是 JSON")
        }
    }

    private suspend fun requestText(
        url: String,
        body: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        expectedGeneration: Long? = null,
    ): String {
        val fullUrl = if (query.isEmpty()) url else url + "?" + query.entries.joinToString("&") {
            "${java.net.URLEncoder.encode(it.key, "UTF-8") }=${java.net.URLEncoder.encode(it.value, "UTF-8") }"
        }
        val request = Request.Builder().url(fullUrl).apply {
            headers.forEach { (name, value) -> header(name, value) }
            if (body == null) get() else post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        }.build()
        return execute(http, request, timeoutMs, expectedGeneration) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpError(response.code)
            text
        }
    }

    private suspend fun requestBytes(
        url: String,
        headers: Map<String, String>,
        expectedGeneration: Long? = null,
    ): ByteArray {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.get().build()
        return execute(http, request, null, expectedGeneration) { response ->
            if (!response.isSuccessful) throw httpError(response.code)
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    private suspend fun <T> execute(
        client: OkHttpClient,
        request: Request,
        timeoutMs: Long?,
        expectedGeneration: Long?,
        transform: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val scoped = timeoutMs?.let { client.newBuilder().callTimeout(it, java.util.concurrent.TimeUnit.MILLISECONDS).build() } ?: client
        val call = scoped.newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
            inFlightCalls.remove(call)
        }
        scope.launch {
            try {
                if (expectedGeneration != null) {
                    credentialLock.withLock {
                        assertGeneration(expectedGeneration)
                        if (!continuation.isActive) return@launch
                        inFlightCalls[call] = expectedGeneration
                    }
                } else if (!continuation.isActive) {
                    return@launch
                }
                val result = call.execute().use { response ->
                    assertGeneration(expectedGeneration)
                    transform(response)
                }
                assertGeneration(expectedGeneration)
                if (continuation.isActive) continuation.resume(result)
            } catch (e: java.io.IOException) {
                if (continuation.isActive) {
                    val dnsFailure = e is java.net.UnknownHostException
                    DiagnosticsLogStore.record(
                        "qq_network", "request_failed",
                        StabilityDiagnostics.networkSnapshot(appContext) + mapOf(
                            "host" to request.url.host,
                            "method" to request.method,
                            "errorType" to e.javaClass.simpleName,
                            "dnsFailure" to dnsFailure,
                        ),
                    )
                    continuation.resumeWith(Result.failure(providerError(
                        MusicProviderError.NETWORK,
                        if (dnsFailure) "QQ 音乐网络解析失败，请检查网络后重试" else "QQ 音乐网络连接失败，请稍后重试",
                    )))
                }
            } catch (e: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            } finally {
                inFlightCalls.remove(call)
            }
        }
    }

    private fun assertGeneration(expectedGeneration: Long?) {
        if (expectedGeneration != null && expectedGeneration != sessionGeneration.get()) {
            throw providerError(MusicProviderError.STALE_SESSION, "QQ Music 会话已退出")
        }
    }

    private fun credential(): QqCredential? = store.loadCredential()?.let(::QqCredential)?.takeIf { it.valid }

    private fun credentialFrom(values: JSONObject, fallbackLoginType: Int): QqCredential {
        val copy = JSONObject(values.toString())
        if (!copy.has("loginType")) copy.put("loginType", fallbackLoginType)
        val credential = QqCredential(copy)
        if (!credential.valid) throw providerError(MusicProviderError.REMOTE, "登录响应缺少 QQ Music 凭据")
        return credential
    }

    private fun accountFrom(profile: JSONObject, credential: QqCredential): MusicProviderAccount {
        val info = profile.optJSONObject("info") ?: profile
        val nickname = info.optString("nick").ifBlank { info.optString("nickname") }
            .ifBlank { credential.values.optString("nick") }
            .ifBlank { credential.values.optString("nickname") }
            .ifBlank { credential.accountId }
        val avatar = sequenceOf(
            info.optString("headpic"),
            info.optString("avatarUrl"),
            credential.values.optString("logo"),
        ).firstOrNull { it.isNotBlank() }
        return MusicProviderAccount(id = credential.accountId, nickname = nickname, avatarUrl = avatar)
    }

    private fun upstreamError(code: Int): MusicProviderException = when (code) {
        1000, 104400, 104401 -> providerError(MusicProviderError.STALE_SESSION, "QQ Music 凭据被上游拒绝 (code=$code)")
        104604 -> providerError(MusicProviderError.RATE_LIMITED, "QQ Music 请求过于频繁 (code=$code)")
        else -> providerError(MusicProviderError.REMOTE, "QQ Music 上游错误 (code=$code)")
    }

    private fun httpError(status: Int): MusicProviderException = when (status) {
        401, 403 -> providerError(MusicProviderError.AUTH_REQUIRED, "QQ Music HTTP $status")
        429 -> providerError(MusicProviderError.RATE_LIMITED, "QQ Music HTTP 429")
        else -> providerError(MusicProviderError.NETWORK, "QQ Music HTTP $status")
    }

    private fun removeExpiredSessions() {
        sessions.entries.removeIf { (_, session) ->
            session.isExpired().also { if (it) session.close() }
        }
    }

    private data class QimeiRequest(val body: JSONObject, val headers: Map<String, String>)

    private data class QqCredential(val values: JSONObject) {
        val accountId: String get() = values.optString("str_musicid").ifBlank { values.optString("musicid") }
        val musickey: String get() = values.optString("musickey")
        val loginType: Int get() = values.optInt("loginType", QQ_LOGIN_TYPE)
        val encryptUin: String? get() = values.optString("encryptUin").takeIf { it.isNotBlank() }
        val valid: Boolean get() = accountId.isNotBlank() && musickey.isNotBlank()
        fun cookieHeader(): String = "uin=$accountId; qqmusic_uin=$accountId; qm_keyst=$musickey; qqmusic_key=$musickey"
    }

    private class QrSession(
        val key: String,
        val channel: String,
        val generation: Long,
        val identifier: String,
        val content: String,
        val qqHttp: QqHttpQrLogin? = null,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        @Volatile var state: QrState = QrState.WAITING
        @Volatile var authorization: JSONObject? = null
        @Volatile var message: String? = null
        @Volatile var nickname: String? = null
        @Volatile var exchange: kotlinx.coroutines.Deferred<QrLoginStatus>? = null
        fun isExpired() = System.currentTimeMillis() - createdAt > QR_TTL_MS
        fun close() {
            exchange?.cancel()
            qqHttp?.close()
        }
    }

    private enum class QrState { WAITING, SCANNED, AUTHORIZED, EXCHANGING, CONFIRMED, EXPIRED, FAILED }

    private companion object {
        const val CHANNEL_QQ = "qq"
        const val CHANNEL_WECHAT = "wechat"
        const val QQ_LOGIN_TYPE = 6
        const val QQ_WEB_LOGIN_TYPE = 2
        const val QQ_LOGIN_REQUEST_TIMEOUT_MS = 15_000L
        const val WECHAT_LOGIN_TYPE = 1
        const val APP_VERSION = 14090008
        const val CHANNEL_ID = "10003505"
        const val MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val QIMEI_URL = "https://api.tencentmusic.com/tme/trpc/proxy"
        const val QIMEI_APP_KEY = "0AND0HD6FE4HY80F"
        const val QIMEI_SECRET = "ZdJqM15EeO2zWc08"
        const val QIMEI_TTL_MS = 24 * 60 * 60 * 1000L
        const val ANDROID_SESSION_TTL_MS = 24 * 60 * 60 * 1000L
        const val QR_TTL_MS = 3 * 60 * 1000L
        const val WECHAT_CONNECT_URL = "https://open.weixin.qq.com/connect/qrconnect"
        const val WECHAT_IMAGE_URL = "https://open.weixin.qq.com/connect/qrcode/"
        const val WECHAT_POLL_URL = "https://lp.open.weixin.qq.com/connect/l/qrconnect"
        const val WECHAT_REDIRECT_URI = "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/"
        const val WECHAT_STYLE_HREF = "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect"
        const val WECHAT_APP_ID = "wx48db31d50e334801"
        const val WECHAT_POLL_TIMEOUT_MS = 2_500L
        const val QR_EXPIRED = 800
        const val QR_WAITING = 801
        const val QR_SCANNED = 802
        const val QR_CONFIRMED = 803
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val UUID_PATTERN = Regex("uuid=(.+?)\\\"")
        val WECHAT_STATUS_PATTERN = Regex("window\\.wx_errcode=(\\d+);window\\.wx_code='([^']*)'")
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val JPEG_MAGIC = byteArrayOf(0xff.toByte(), 0xd8.toByte())
    }
}

private fun providerError(code: MusicProviderError, message: String): MusicProviderException =
    MusicProviderException("qq", code, message)

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun JSONObject.objectValue(name: String): JSONObject = optJSONObject(name)
    ?: optString(name).takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    ?: JSONObject()

private fun md5(vararg parts: String): String = MessageDigest.getInstance("MD5")
    .digest(parts.joinToString("").toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

private fun rsaEncrypt(value: ByteArray): ByteArray = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
    init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(QIMEI_PUBLIC_KEY)))
    doFinal(value)
}

private fun aesCbcEncrypt(key: ByteArray, value: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
    init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), IvParameterSpec(key))
    doFinal(value)
}

private fun randomBeaconId(): String {
    val random = SecureRandom()
    val month = java.text.SimpleDateFormat("yyyy-MM-01", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
    return (1..40).joinToString(separator = "") { index ->
        val value = when (index) {
            1, 2, 13, 14, 17, 18, 21, 22, 25, 26, 29, 30, 33, 34, 37, 38 -> "$month${100000 + random.nextInt(900000)}.${100000000 + random.nextInt(900000000)}"
            3 -> "0000000000000000"
            4 -> ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
            else -> random.nextInt(10_000).toString()
        }
        "k$index:$value;"
    }
}

private val QIMEI_PUBLIC_KEY = Base64.getDecoder().decode(
    "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDEIxgwoutfwoJxcGQeedgP7FG9qaIuS0qzfR8gWkrkTZKM2iWHn2ajQpBRZjMSoSf6+KJGvar2ORhBfpDXyVtZCKpqLQ+FLkpncClKVIrBwv6PHyUvuCb0rIarmgDnzkfQAqVufEtR64iazGDKatvJ9y6B9NMbHddGSAUmRTCrHQIDAQAB",
)
