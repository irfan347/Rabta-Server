package com.irfan.rabta

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

/** Real signed, expired test records. No Android accounts, Keystore or production storage. */
class InstantQrTest {
    private fun decode(raw:String,size:Int,mask:Int?=null):String {
        val hints=if(mask==null)emptyMap() else mapOf(EncodeHintType.QR_MASK_PATTERN to mask)
        val matrix=QRCodeWriter().encode(raw,BarcodeFormat.QR_CODE,size,size,hints)
        val pixels=IntArray(matrix.width*matrix.height) { index ->
            if(matrix[index%matrix.width,index/matrix.width])0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        // Exercise the ordinary detector. No PURE_BARCODE, retries, catch-and-ignore or supplied points.
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width,matrix.height,pixels))),InstantQr.decodeHints).text
    }
    @Test fun previouslyMissedConfirmationDecodesAtBothRenderingSizesAndEveryMask() {
        val raw=javaClass.getResourceAsStream("/qr-regression.txt")!!.bufferedReader().use { it.readText().trim() }
        assertEquals(InstantInvitation.CONFIRMATION,InstantInvitation.verify(raw,1700000000002L).record.kind)
        assertTrue("Fixture must exercise a dense QR",Encoder.encode(raw,ErrorCorrectionLevel.L).version.versionNumber>20)
        for(size in listOf(800,900)) {
            assertEquals("Default mask size=$size",raw,decode(raw,size))
            for(mask in 0..7)assertEquals("Mask=$mask size=$size",raw,decode(raw,size,mask))
        }
    }
    @Test fun signedPairingImagesDecodeAcrossStagesAndRasterSizes() {
        val a=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val b=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now=1700000000000L
        repeat(32) { sample ->
            val offer=InstantInvitation.offer("AAAABBBBCCCC","QR fixture $sample",a,now)
            val reply=InstantInvitation.reply(offer,"DDDDEEEEFFFF","علی",b,now+1)
            val confirmation=InstantInvitation.confirmation(offer,reply,a,now+2)
            for(packet in listOf(offer,reply,confirmation))for(size in listOf(800,900)) {
                assertEquals("Stage="+packet.record.kind+" size="+size+" sample="+sample,packet.raw,decode(packet.raw,size))
            }
        }
    }

    @Test fun diagnosePreservedDenseQrDetection() {
        val raw=javaClass.getResourceAsStream("/qr-regression.txt")!!.bufferedReader().use { it.readText().trim() }
        val summary=StringBuilder("Dense QR diagnostic (expired public fixture only)\n")
        for(size in listOf(800,900))for(mask in -1..7) {
            val hints=if(mask<0)emptyMap() else mapOf(EncodeHintType.QR_MASK_PATTERN to mask)
            val matrix=QRCodeWriter().encode(raw,BarcodeFormat.QR_CODE,size,size,hints)
            var pixels=IntArray(size*size) { if(matrix[it%size,it/size])0xFF000000.toInt() else -1 }
            val attempts=mutableListOf<String>()
            repeat(4) { rotation ->
                val bitmap=BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size,size,pixels)))
                val direct=runCatching { com.google.zxing.qrcode.QRCodeReader().decode(bitmap,InstantQr.decodeHints).text }
                val pure=runCatching { com.google.zxing.qrcode.QRCodeReader().decode(bitmap,mapOf(com.google.zxing.DecodeHintType.PURE_BARCODE to true)).text }
                val detected=runCatching {
                    val found=com.google.zxing.qrcode.detector.Detector(bitmap.blackMatrix).detect(InstantQr.decodeHints)
                    val points=found.points.joinToString { "("+it.x+","+it.y+")" }
                    val decoded=runCatching { com.google.zxing.qrcode.decoder.Decoder().decode(found.bits,InstantQr.decodeHints).text }
                    "dim="+found.bits.width+" points="+points+" data="+if(decoded.getOrNull()==raw)"ok" else decoded.exceptionOrNull()?.javaClass?.simpleName
                }
                attempts.add("r="+(rotation*90)+" normal="+(if(direct.getOrNull()==raw)"ok" else direct.exceptionOrNull()?.javaClass?.simpleName)+
                    " pure="+(if(pure.getOrNull()==raw)"ok" else pure.exceptionOrNull()?.javaClass?.simpleName)+
                    " detect="+detected.getOrElse { it.javaClass.simpleName })
                val old=pixels
                pixels=IntArray(size*size) { i -> old[(size-1-i%size)*size+i/size] }
            }
            summary.append("size=").append(size).append(" mask=").append(mask).append(" sameBits=")
                .append(matrix==BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size,size,IntArray(size*size) { if(matrix[it%size,it/size])0xFF000000.toInt() else -1 }))).blackMatrix)
                .append(" ").append(attempts.joinToString(" | ")).append("\n")
        }
        throw AssertionError(summary.toString())
    }
}
