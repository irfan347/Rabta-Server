package com.irfan.rabta
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
class BackupCryptoTest {
    private fun backup():ByteArray {val out=ByteArrayOutputStream();BackupCrypto.encrypt(out,"correct password".toCharArray()){it.write("private chats".toByteArray())};return out.toByteArray()}
    @Test fun roundTrip(){val out=ByteArrayOutputStream();BackupCrypto.decrypt(ByteArrayInputStream(backup()),"correct password".toCharArray(),out);assertEquals("private chats",out.toString("UTF-8"))}
    @Test fun wrongPasswordAndTamperingFail(){val original=backup();for(bytes in listOf(original,original.clone().apply {this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()})){try{BackupCrypto.decrypt(ByteArrayInputStream(bytes),(if(bytes===original)"wrong password" else "correct password").toCharArray(),ByteArrayOutputStream());fail("Must reject unauthenticated backup")}catch(expected:javax.crypto.AEADBadTagException){}}}
    @Test fun randomizedEncryption(){assertFalse(backup().contentEquals(backup()))}
}
