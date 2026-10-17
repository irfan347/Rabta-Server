package com.irfan.rabta

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator

/** Isolated expired public test invitations; never accesses Android accounts or Keystore. */
class InstantQrTest {
    @Test fun signedPairingImagesDecodeAcrossStagesAndRasterSizes() {
        val a=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val b=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now=1700000000000L
        repeat(32) { sample ->
            val offer=InstantInvitation.offer("AAAABBBBCCCC","QR fixture $sample",a,now)
            val reply=InstantInvitation.reply(offer,"DDDDEEEEFFFF","علی",b,now+1)
            val confirmation=InstantInvitation.confirmation(offer,reply,a,now+2)
            for(packet in listOf(offer,reply,confirmation))for(size in listOf(800,900)) {
                val matrix=QRCodeWriter().encode(packet.raw,BarcodeFormat.QR_CODE,size,size)
                val pixels=IntArray(matrix.width*matrix.height) { index ->
                    if(matrix[index%matrix.width,index/matrix.width])0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
                try {
                    val actual=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width,matrix.height,pixels)))).text
                    assertEquals(packet.raw,actual)
                }catch(error:Exception) {
                    throw AssertionError("QR stage="+packet.record.kind+" size="+size+" sample="+sample+
                        " expired-public-fixture="+packet.raw,error)
                }
            }
        }
    }
}
