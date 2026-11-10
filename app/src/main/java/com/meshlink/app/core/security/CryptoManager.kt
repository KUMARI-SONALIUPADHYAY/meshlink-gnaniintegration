package com.meshlink.app.core.security

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoManager(private val identity: DeviceIdentity) {
    private val sessions = ConcurrentHashMap<UUID, ByteArray>()
    fun publicKey(): ByteArray = identity.publicKeyEncoded()
    fun registerPeer(nodeId: UUID, encodedPublicKey: ByteArray): Boolean = try {
        val peer=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encodedPublicKey))
        val secret=KeyAgreement.getInstance("ECDH").apply {
            init(identity.keyPair().private); doPhase(peer,true)
        }.generateSecret()
        val ordered = listOf(identity.nodeId.toString(), nodeId.toString()).sorted()
        val mac=Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret,"HmacSHA256")); update(ordered[0].toByteArray()); update(ordered[1].toByteArray()) }
        sessions[nodeId]=mac.doFinal().copyOf(32); true
    } catch(_:Throwable){false}

    fun hasSession(nodeId:UUID)=sessions.containsKey(nodeId)
    fun encrypt(peer: UUID, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val key=sessions[peer] ?: error("No authenticated BLE session")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce)); updateAAD(aad); doFinal(plain)
        }
    }
    fun decrypt(peer: UUID, nonce: ByteArray, aad: ByteArray, cipherText: ByteArray): ByteArray? {
        return try {
            val key=sessions[peer] ?: return null
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce))
                updateAAD(aad)
                doFinal(cipherText)
            }
        } catch(_:Throwable){null}
    }
}
