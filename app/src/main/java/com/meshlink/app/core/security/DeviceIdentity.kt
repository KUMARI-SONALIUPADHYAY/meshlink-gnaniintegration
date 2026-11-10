package com.meshlink.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val ksName = "AndroidKeyStore"
    private val alias = "meshlink-device-signing"
    private val prefs = context.getSharedPreferences("mesh_identity", Context.MODE_PRIVATE)
    val nodeId: UUID = UUID.fromString(prefs.getString("node_id", null) ?: UUID.randomUUID().toString()).also {
        prefs.edit().putString("node_id", it.toString()).apply()
    }
    var displayName: String = prefs.getString("display_name", null) ?: "Mesh-${nodeId.toString().takeLast(4)}"
        private set
    var avatarIndex: Int = prefs.getInt("avatar_index", 0)
        private set

    fun setDisplayName(name: String) {
        val clean = name.trim().replace("\n", " ").take(32)
        if (clean.isBlank()) return
        displayName = clean
        prefs.edit().putString("display_name", clean).apply()
    }

    fun setAvatarIndex(index: Int) {
        avatarIndex = index.coerceIn(0, 7)
        prefs.edit().putInt("avatar_index", avatarIndex).apply()
    }

    init { ensureKeyPair() }

    fun keyPair(): KeyPair {
        val ks=KeyStore.getInstance(ksName).apply{load(null)}
        val entry=ks.getEntry(alias,null) as KeyStore.PrivateKeyEntry
        return KeyPair(entry.certificate.publicKey,entry.privateKey)
    }
    private fun ensureKeyPair() {
        val ks=KeyStore.getInstance(ksName).apply{load(null)}
        if(!ks.containsAlias(alias)) {
            val gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,ksName)
            gen.initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_AGREE_KEY)
                .setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA512).build())
            gen.generateKeyPair()
        }
    }
    fun publicKeyEncoded(): ByteArray = keyPair().public.encoded
}
