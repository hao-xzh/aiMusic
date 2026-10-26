package app.pipo.nativeapp.data.qqnative

import android.os.Build
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/** 同一安装周期内稳定的 Android 协议设备上下文；不读取电话标识或申请额外权限。 */
internal data class QqDeviceContext(
    val display: String,
    val product: String,
    val device: String,
    val board: String,
    val model: String,
    val fingerprint: String,
    val procVersion: String,
    val imei: String,
    val brand: String,
    val androidId: String,
    val openUdid: String,
    val osRelease: String,
    val sdk: Int,
    val qimei: String? = null,
    val qimei36: String? = null,
    val qimeiSavedAt: Long? = null,
    val sessionUid: String? = null,
    val sessionSid: String? = null,
    val sessionVkey: String? = null,
    val sessionSavedAt: Long? = null,
) {
    fun withQimei(q16: String, q36: String, savedAt: Long) = copy(
        qimei = q16,
        qimei36 = q36,
        qimeiSavedAt = savedAt,
    )

    fun withSession(uid: String, sid: String, vkey: String?, savedAt: Long) = copy(
        sessionUid = uid,
        sessionSid = sid,
        sessionVkey = vkey,
        sessionSavedAt = savedAt,
    )

    fun toJson() = JSONObject().apply {
        put("display", display)
        put("product", product)
        put("device", device)
        put("board", board)
        put("model", model)
        put("fingerprint", fingerprint)
        put("procVersion", procVersion)
        put("imei", imei)
        put("brand", brand)
        put("androidId", androidId)
        put("openUdid", openUdid)
        put("osRelease", osRelease)
        put("sdk", sdk)
        qimei?.let { put("qimei", it) }
        qimei36?.let { put("qimei36", it) }
        qimeiSavedAt?.let { put("qimeiSavedAt", it) }
        sessionUid?.let { put("sessionUid", it) }
        sessionSid?.let { put("sessionSid", it) }
        sessionVkey?.let { put("sessionVkey", it) }
        sessionSavedAt?.let { put("sessionSavedAt", it) }
    }

    companion object {
        fun create(): QqDeviceContext {
            val random = SecureRandom()
            fun hex(count: Int) = ByteArray(count).also(random::nextBytes)
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
            val seed = hex(16)
            val stable = md5(seed).lowercase(Locale.US)
            return QqDeviceContext(
                display = Build.DISPLAY.orEmpty(),
                product = Build.PRODUCT.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                board = Build.BOARD.orEmpty(),
                model = Build.MODEL.orEmpty(),
                fingerprint = Build.FINGERPRINT.orEmpty(),
                procVersion = System.getProperty("os.version").orEmpty(),
                imei = randomDigits(random),
                brand = Build.BRAND.orEmpty(),
                androidId = stable.take(16),
                openUdid = stable,
                osRelease = Build.VERSION.RELEASE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
            )
        }

        fun fromJson(value: JSONObject): QqDeviceContext? {
            val required = listOf("display", "product", "device", "board", "model", "fingerprint", "procVersion", "imei", "brand", "androidId", "openUdid", "osRelease")
            if (required.any { !value.has(it) }) return null
            return QqDeviceContext(
                display = value.optString("display"),
                product = value.optString("product"),
                device = value.optString("device"),
                board = value.optString("board"),
                model = value.optString("model"),
                fingerprint = value.optString("fingerprint"),
                procVersion = value.optString("procVersion"),
                imei = value.optString("imei"),
                brand = value.optString("brand"),
                androidId = value.optString("androidId"),
                openUdid = value.optString("openUdid"),
                osRelease = value.optString("osRelease"),
                sdk = value.optInt("sdk"),
                qimei = value.optNonBlank("qimei"),
                qimei36 = value.optNonBlank("qimei36"),
                qimeiSavedAt = value.takeIf { it.has("qimeiSavedAt") }?.optLong("qimeiSavedAt"),
                sessionUid = value.optNonBlank("sessionUid"),
                sessionSid = value.optNonBlank("sessionSid"),
                sessionVkey = value.optNonBlank("sessionVkey"),
                sessionSavedAt = value.takeIf { it.has("sessionSavedAt") }?.optLong("sessionSavedAt"),
            )
        }

        private fun randomDigits(random: SecureRandom): String = buildString {
            append((random.nextInt(9) + 1).toString())
            repeat(14) { append(random.nextInt(10).toString()) }
        }

        private fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
}

private fun JSONObject.optNonBlank(name: String): String? = optString(name).takeIf { it.isNotBlank() }
