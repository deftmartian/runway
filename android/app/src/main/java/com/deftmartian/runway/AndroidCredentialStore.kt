package com.deftmartian.runway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AndroidCredential(
    val origin: String,
    val deviceId: String,
    val token: String,
    val expiresAtEpochMs: Long,
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean = expiresAtEpochMs <= nowEpochMs
}

internal object AndroidCredentialNamespace {
    fun originKey(origin: String): String = MessageDigest.getInstance("SHA-256")
        .digest(origin.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun credentialKey(origin: String): String = "paired_device_v2_${originKey(origin)}"

    fun keyAlias(origin: String): String = "runway_android_device_credential_v2_${originKey(origin)}"

    fun associatedData(applicationId: String, origin: String): ByteArray =
        "$applicationId\u0000$origin".toByteArray(StandardCharsets.UTF_8)
}

class AndroidCredentialStore(context: Context, origin: String) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val expectedOrigin = requireNotNull(
        InstanceOriginPolicy.normalizeOrigin(origin, BuildConfig.DEBUG),
    ) { "Android credentials require a valid runway origin" }
    private val credentialKey = AndroidCredentialNamespace.credentialKey(expectedOrigin)
    private val keyAlias = AndroidCredentialNamespace.keyAlias(expectedOrigin)
    private val associatedData = AndroidCredentialNamespace.associatedData(
        BuildConfig.APPLICATION_ID,
        expectedOrigin,
    )

    init {
        // 0.2.0 intentionally invalidates the pre-server-selection global slot. It cannot be safely
        // attributed to an origin, and retaining it would let one server's lifecycle touch another.
        preferences.edit(commit = true) { remove(LEGACY_CREDENTIAL_KEY) }
    }

    fun save(credential: AndroidCredential): Boolean = runCatching {
        require(credential.deviceId.isNotBlank())
        require(credential.token.startsWith("rwy1_"))
        require(!credential.isExpired())
        require(credential.origin == expectedOrigin)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        val plaintext = JSONObject()
            .put("origin", credential.origin)
            .put("deviceId", credential.deviceId)
            .put("token", credential.token)
            .put("expiresAtEpochMs", credential.expiresAtEpochMs)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        val encoded = Base64.encodeToString(
            cipher.iv + ciphertext,
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
        preferences.edit { putString(credentialKey, encoded) }
        true
    }.getOrElse {
        clearStoredValue()
        false
    }

    fun load(): AndroidCredential? {
        val encoded = preferences.getString(credentialKey, null) ?: return null
        return runCatching {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
            require(encrypted.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, encrypted.copyOfRange(0, GCM_IV_BYTES)),
            )
            cipher.updateAAD(associatedData)
            val plaintext = cipher.doFinal(encrypted.copyOfRange(GCM_IV_BYTES, encrypted.size))
            val payload = JSONObject(String(plaintext, StandardCharsets.UTF_8))
            val credential = AndroidCredential(
                origin = payload.getString("origin"),
                deviceId = payload.getString("deviceId"),
                token = payload.getString("token"),
                expiresAtEpochMs = payload.getLong("expiresAtEpochMs"),
            )
            if (credential.isExpired() || credential.origin != expectedOrigin) {
                clearStoredValue()
                null
            } else {
                credential
            }
        }.getOrElse {
            clearStoredValue()
            null
        }
    }

    fun clear() {
        clearStoredValue()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    private fun clearStoredValue() {
        preferences.edit { remove(credentialKey) }
    }

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

    companion object {
        internal fun clearLegacyState(context: Context) {
            val appContext = context.applicationContext
            appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit(commit = true) { remove(LEGACY_CREDENTIAL_KEY) }
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(LEGACY_KEY_ALIAS)) keyStore.deleteEntry(LEGACY_KEY_ALIAS)
            }
        }

        private const val PREFERENCES_NAME = "runway_android_credentials"
        private const val LEGACY_CREDENTIAL_KEY = "paired_device"
        private const val LEGACY_KEY_ALIAS = "runway_android_device_credential_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
