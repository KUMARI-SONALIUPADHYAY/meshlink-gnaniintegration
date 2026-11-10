package com.meshlink.app.core.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores short-lived cloud access tokens encrypted with a non-exportable Android Keystore key. */
class EncryptedCloudTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_auth_secure", Context.MODE_PRIVATE)
    private val alias = "meshlink_cloud_token_v1"

    @Synchronized fun save(token: String, userId: String) {
        require(token.isNotBlank() && token.length <= 8192 && userId.isNotBlank() && userId.length <= 128)
        val payload = org.json.JSONObject().put("token", token).put("userId", userId).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun decryptedPayload(): String? = runCatching {
        val iv = prefs.getString("iv", null) ?: return null
        val ciphertext = prefs.getString("ciphertext", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized fun read(): String? = decryptedPayload()?.let { value -> runCatching { org.json.JSONObject(value).getString("token") }.getOrElse { value } }
    @Synchronized fun readUserId(): String? = decryptedPayload()?.let { value -> runCatching { org.json.JSONObject(value).getString("userId") }.getOrNull() }

    @Synchronized fun clear() { prefs.edit().clear().apply() }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
