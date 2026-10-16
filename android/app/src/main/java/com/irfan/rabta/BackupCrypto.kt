package com.irfan.rabta

import java.io.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
object BackupCrypto {
    private val magic="RABTA19\n".toByteArray(Charsets.US_ASCII)
    private fun cipher(mode:Int,password:CharArray,salt:ByteArray,iv:ByteArray):Cipher {
        val spec=PBEKeySpec(password,salt,210000,256)
        val key=try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));updateAAD(magic);key.fill(0) }
    }
    fun encrypt(out:OutputStream,password:CharArray,write:(OutputStream)->Unit) {
        val salt=ByteArray(16).also { SecureRandom().nextBytes(it) };val iv=ByteArray(12).also { SecureRandom().nextBytes(it) }
        out.write(magic);out.write(salt);out.write(iv);CipherOutputStream(out,cipher(Cipher.ENCRYPT_MODE,password,salt,iv)).use(write)
    }
    fun decrypt(input:InputStream,password:CharArray,output:OutputStream) {
        val d=DataInputStream(input);val header=ByteArray(magic.size);d.readFully(header);require(header.contentEquals(magic)){"Unsupported backup"}
        val salt=ByteArray(16);val iv=ByteArray(12);d.readFully(salt);d.readFully(iv)
        val cipher=cipher(Cipher.DECRYPT_MODE,password,salt,iv);val buffer=ByteArray(8192);var total=0L
        while(true){val n=d.read(buffer);if(n<0)break;total+=n;require(total<=128L*1024*1024){"Backup is too large"};cipher.update(buffer,0,n)?.let { output.write(it) }}
        output.write(cipher.doFinal()) // Authentication must succeed before callers restore any data.
    }
}
