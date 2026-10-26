package app.pipo.nativeapp.data.qqnative

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * QQ Music 的凭据和设备上下文只以 AES-GCM 密文保存在 app 私有 Preferences 中。
 * 密钥由 AndroidKeyStore 持有，musickey 不会通过公开属性、URL 或日志离开本类。
 */
internal class QqCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun loadCredential(): JSONObject? = load(CREDENTIAL_KEY)

    fun saveCredential(value: JSONObject) = save(CREDENTIAL_KEY, value)

    fun clearCredential() {
        preferences.edit().remove(CREDENTIAL_KEY).apply()
    }

    fun loadDevice(): QqDeviceContext? = load(DEVICE_KEY)?.let(QqDeviceContext::fromJson)

    fun saveDevice(value: QqDeviceContext) = save(DEVICE_KEY, value.toJson())

    private fun load(key: String): JSONObject? {
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching { JSONObject(decrypt(encoded)) }.getOrNull()
    }

    private fun save(key: String, value: JSONObject) {
        preferences.edit().putString(key, encrypt(value.toString())).apply()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore generates the IV for randomized encryption.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Invalid QQ session IV length" }
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > IV_BYTES) { "Invalid encrypted QQ session" }
        val iv = packed.copyOfRange(0, IV_BYTES)
        val encrypted = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val PREFERENCES = "qq_native_secure_session"
        const val CREDENTIAL_KEY = "credential"
        const val DEVICE_KEY = "device"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "qq_native_session_v1"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
