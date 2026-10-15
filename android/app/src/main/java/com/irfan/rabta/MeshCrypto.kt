package com.irfan.rabta

import java.security.*
import java.security.spec.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.*

/** Standard authenticated hybrid encryption; routing metadata is authenticated too. */
object MeshCrypto {
    data class Box(val key:String,val iv:String,val ciphertext:String)
    fun b64(b:ByteArray)=Base64.getEncoder().encodeToString(b)
    fun bytes(s:String)=Base64.getDecoder().decode(s)
    fun publicKey(s:String):PublicKey {
        require(s.length in 300..1000)
        val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes(s)))
        require((key as java.security.interfaces.RSAPublicKey).modulus.bitLength()==2048)
        return key
    }
    fun fingerprint(key:String)=MessageDigest.getInstance("SHA-256").digest(bytes(key)).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
    private fun rsa(mode:Int,key:Key)=Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(mode,key,OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT)) }
    fun seal(publicKey:PublicKey,plain:String,aad:String):Box {
        val aes=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv=ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,aes,GCMParameterSpec(128,iv)); updateAAD(aad.toByteArray()) }
        return Box(b64(rsa(Cipher.ENCRYPT_MODE,publicKey).doFinal(aes.encoded)),b64(iv),b64(cipher.doFinal(plain.toByteArray())))
    }
    fun open(privateKey:PrivateKey,box:Box,aad:String):String {
        val aes=SecretKeySpec(rsa(Cipher.DECRYPT_MODE,privateKey).doFinal(bytes(box.key)),"AES")
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,aes,GCMParameterSpec(128,bytes(box.iv))); updateAAD(aad.toByteArray()) }
        return String(cipher.doFinal(bytes(box.ciphertext)),Charsets.UTF_8)
    }
    fun sign(key:PrivateKey,data:String)=b64(Signature.getInstance("SHA256withRSA").run { initSign(key); update(data.toByteArray()); sign() })
    fun verify(key:PublicKey,data:String,sig:String)=runCatching { Signature.getInstance("SHA256withRSA").run { initVerify(key); update(data.toByteArray()); verify(bytes(sig)) } }.getOrDefault(false)
}
