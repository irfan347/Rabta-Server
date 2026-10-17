package com.irfan.rabta

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * This phone's sign-in identity: an EC P-256 key created inside Android Keystore that never leaves the phone.
 * The server trusts phones by this key, so a stolen PIN alone cannot sign in elsewhere. Reinstalling the app
 * removes the key, after which the phone is new to the account and needs approval or the recovery code.
 */
object DeviceIdentity {
    private const val ALIAS = "rabta_device_v1"

    @Synchronized private fun pair(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return KeyPair(it.certificate.publicKey, it.privateKey) }
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
    }

    val id: String get() = MessageDigest.getInstance("SHA-256").digest(pair().public.encoded).joinToString("") { "%02x".format(it) }.take(32)

    /** A single-use, time-limited signature binding this phone to [purpose] (e.g. "login:ali"). */
    fun proof(purpose: String): JSONObject {
        val keys = pair(); val der = keys.public.encoded
        val id = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }.take(32)
        val time = System.currentTimeMillis()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update("rabta-device\n$purpose\n$id\n$time".toByteArray()); sign() }
        return JSONObject().put("id", id).put("key", Base64.getEncoder().encodeToString(der))
            .put("name", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(40)).put("time", time)
            .put("sig", Base64.getEncoder().encodeToString(sig))
    }
}
