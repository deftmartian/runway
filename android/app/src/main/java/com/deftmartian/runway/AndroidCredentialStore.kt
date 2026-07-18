package com.deftmartian.runway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AndroidCredential(
    val deviceId: String,
    val token: String,
    val expiresAtEpochMs: Long,
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean = expiresAtEpochMs <= nowEpochMs
}

class AndroidCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val associatedData = BuildConfig.APPLICATION_ID.toByteArray(StandardCharsets.UTF_8)

    fun save(credential: AndroidCredential): Boolean = runCatching {
        require(credential.deviceId.isNotBlank())
        require(credential.token.startsWith("rwy1_"))
        require(!credential.isExpired())
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        val plaintext = JSONObject()
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
        preferences.edit { putString(CREDENTIAL_KEY, encoded) }
        true
    }.getOrElse {
        clearStoredValue()
        false
    }

    fun load(): AndroidCredential? {
        val encoded = preferences.getString(CREDENTIAL_KEY, null) ?: return null
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
                deviceId = payload.getString("deviceId"),
                token = payload.getString("token"),
                expiresAtEpochMs = payload.getLong("expiresAtEpochMs"),
            )
            if (credential.isExpired()) {
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
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun clearStoredValue() {
        preferences.edit { remove(CREDENTIAL_KEY) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
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
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "runway_android_credentials"
        const val CREDENTIAL_KEY = "paired_device"
        const val KEY_ALIAS = "runway_android_device_credential_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
