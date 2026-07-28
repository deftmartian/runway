package dev.deftmartian.runway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MobileSession(
    val origin: String,
    val token: String,
    val expiresAtEpochMs: Long,
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs <= nowEpochMs

    override fun toString(): String =
        "MobileSession(origin=$origin, token=<redacted>, expiresAtEpochMs=$expiresAtEpochMs)"
}

data class PendingMobileAuthorization(
    val origin: String,
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtEpochMs: Long,
    val pollIntervalSeconds: Int,
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs <= nowEpochMs
}

internal object MobileSessionNamespace {
    fun originKey(origin: String): String = MessageDigest.getInstance("SHA-256")
        .digest(origin.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun sessionKey(origin: String): String = "mobile_session_v1_${originKey(origin)}"

    fun pendingKey(origin: String): String = "mobile_authorization_v1_${originKey(origin)}"

    fun keyAlias(origin: String): String = "runway_mobile_session_v1_${originKey(origin)}"

    fun associatedData(applicationId: String, origin: String, slot: String): ByteArray =
        "$applicationId\u0000$origin\u0000$slot".toByteArray(StandardCharsets.UTF_8)
}

class MobileSessionStore(context: Context, origin: String) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val expectedOrigin = requireNotNull(
        InstanceOriginPolicy.normalizeOrigin(origin, BuildConfig.DEBUG),
    ) { "Mobile sessions require a valid runway origin" }
    private val sessionKey = MobileSessionNamespace.sessionKey(expectedOrigin)
    private val pendingKey = MobileSessionNamespace.pendingKey(expectedOrigin)
    private val keyAlias = MobileSessionNamespace.keyAlias(expectedOrigin)

    fun loadSession(): MobileSession? = AndroidStateCoordinator.read {
        decrypt(preferences.getString(sessionKey, null), SESSION_SLOT)
            ?.let(::decodeSession)
            ?.takeIf(::validSession)
    }

    fun saveSession(session: MobileSession): Boolean = AndroidStateCoordinator.write {
        if (!validSession(session)) return@write false
        val payload = JSONObject()
            .put("origin", session.origin)
            .put("token", session.token)
            .put("expiresAtEpochMs", session.expiresAtEpochMs)
        val encoded = encrypt(payload, SESSION_SLOT) ?: return@write false
        preferences.edit(commit = true) {
            putString(sessionKey, encoded)
            remove(pendingKey)
        }
        true
    }

    fun loadPending(): PendingMobileAuthorization? = AndroidStateCoordinator.read {
        decrypt(preferences.getString(pendingKey, null), PENDING_SLOT)
            ?.let(::decodePending)
            ?.takeIf(::validPending)
    }

    fun savePending(pending: PendingMobileAuthorization): Boolean = AndroidStateCoordinator.write {
        if (!validPending(pending)) return@write false
        val payload = JSONObject()
            .put("origin", pending.origin)
            .put("deviceCode", pending.deviceCode)
            .put("userCode", pending.userCode)
            .put("verificationUri", pending.verificationUri)
            .put("expiresAtEpochMs", pending.expiresAtEpochMs)
            .put("pollIntervalSeconds", pending.pollIntervalSeconds)
        val encoded = encrypt(payload, PENDING_SLOT) ?: return@write false
        preferences.edit(commit = true) { putString(pendingKey, encoded) }
        true
    }

    fun clearPending() {
        AndroidStateCoordinator.write {
            preferences.edit(commit = true) { remove(pendingKey) }
        }
    }

    fun clear() {
        AndroidStateCoordinator.write {
            preferences.edit(commit = true) {
                remove(sessionKey)
                remove(pendingKey)
            }
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
            }
        }
    }

    private fun validSession(session: MobileSession): Boolean =
        session.origin == expectedOrigin &&
            session.token.length in 20..1_024 &&
            session.token.all { it.code in 0x21..0x7e } &&
            !session.isExpired()

    private fun validPending(pending: PendingMobileAuthorization): Boolean =
        pending.origin == expectedOrigin &&
            pending.deviceCode.length in 20..256 &&
            pending.userCode.matches(Regex("[A-HJ-NP-Z2-9]{8}")) &&
            InstanceOriginPolicy.belongsTo(pending.verificationUri, expectedOrigin) &&
            pending.pollIntervalSeconds in 1..60 &&
            !pending.isExpired()

    private fun decodeSession(payload: JSONObject): MobileSession? = runCatching {
        MobileSession(
            origin = payload.getString("origin"),
            token = payload.getString("token"),
            expiresAtEpochMs = payload.getLong("expiresAtEpochMs"),
        )
    }.getOrNull()

    private fun decodePending(payload: JSONObject): PendingMobileAuthorization? = runCatching {
        PendingMobileAuthorization(
            origin = payload.getString("origin"),
            deviceCode = payload.getString("deviceCode"),
            userCode = payload.getString("userCode"),
            verificationUri = payload.getString("verificationUri"),
            expiresAtEpochMs = payload.getLong("expiresAtEpochMs"),
            pollIntervalSeconds = payload.getInt("pollIntervalSeconds"),
        )
    }.getOrNull()

    private fun encrypt(payload: JSONObject, slot: String): String? = runCatching {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(MobileSessionNamespace.associatedData(BuildConfig.APPLICATION_ID, expectedOrigin, slot))
        val ciphertext = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))
        Base64.encodeToString(
            cipher.iv + ciphertext,
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
    }.getOrNull()

    private fun decrypt(encoded: String?, slot: String): JSONObject? = runCatching {
        requireNotNull(encoded)
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
        require(encrypted.size > GCM_IV_BYTES)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, encrypted.copyOfRange(0, GCM_IV_BYTES)),
        )
        cipher.updateAAD(MobileSessionNamespace.associatedData(BuildConfig.APPLICATION_ID, expectedOrigin, slot))
        JSONObject(
            String(
                cipher.doFinal(encrypted.copyOfRange(GCM_IV_BYTES, encrypted.size)),
                StandardCharsets.UTF_8,
            ),
        )
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "runway_mobile_sessions"
        const val SESSION_SLOT = "session"
        const val PENDING_SLOT = "pending"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
