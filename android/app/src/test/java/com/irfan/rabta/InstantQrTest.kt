package com.irfan.rabta

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

/** Real signed, expired test records. No Android accounts, Keystore or production storage. */
class InstantQrTest {
    private fun decode(raw:String,size:Int,mask:Int?=null,turns:Int=0):String {
        val hints=if(mask==null)emptyMap() else mapOf(EncodeHintType.QR_MASK_PATTERN to mask)
        val matrix=QRCodeWriter().encode(raw,BarcodeFormat.QR_CODE,size,size,hints)
        var pixels=IntArray(matrix.width*matrix.height) { index ->
            if(matrix[index%matrix.width,index/matrix.width])0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        repeat(turns) {
            val old=pixels
            pixels=IntArray(size*size) { old[(size-1-it%size)*size+it/size] }
        }
        // Exercise the actual camera decoder: bounded ordinary-detection recovery, never PURE_BARCODE.
        return InstantQrDecoderFactory().createDecoder(mutableMapOf<com.google.zxing.DecodeHintType,Any>()).decode(RGBLuminanceSource(matrix.width,matrix.height,pixels))?.text?:error("Actual scanner could not decode size=$size mask=$mask")
    }
    @Test fun previouslyMissedConfirmationDecodesAtBothRenderingSizesAndEveryMask() {
        val raw=javaClass.getResourceAsStream("/qr-regression.txt")!!.bufferedReader().use { it.readText().trim() }
        assertEquals(InstantInvitation.CONFIRMATION,InstantInvitation.verify(raw,1700000000002L).record.kind)
        assertTrue("Fixture must exercise a dense QR",Encoder.encode(raw,ErrorCorrectionLevel.L).version.versionNumber>20)
        for(size in listOf(800,900)) {
            assertEquals("Default mask size=$size",raw,decode(raw,size))
            for(mask in 0..7)for(turn in 0..3)assertEquals("Mask=$mask size=$size turns=$turn",raw,decode(raw,size,mask,turn))
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

    @Test fun realDecoderRecoversRotatedRectangularFramesAndMapsFinderPoints() {
        val raw=javaClass.getResourceAsStream("/qr-regression.txt")!!.bufferedReader().use { it.readText().trim() }
        val matrix=QRCodeWriter().encode(raw,BarcodeFormat.QR_CODE,800,900)
        var width=matrix.width;var height=matrix.height
        var pixels=IntArray(width*height) { if(matrix[it%width,it/width])0xFF000000.toInt() else -1 }
        var expected=listOf(70f to 120f,70f to 780f,730f to 120f)
        repeat(4) {
            val result=InstantQrDecoderFactory().createDecoder(mutableMapOf<com.google.zxing.DecodeHintType,Any>()).decode(RGBLuminanceSource(width,height,pixels))
            assertNotNull("Orientation "+it,result);assertEquals(raw,result!!.text)
            assertEquals(BarcodeFormat.QR_CODE,result.barcodeFormat)
            expected.forEach { (x,y) -> assertTrue("Finder point must stay in original frame coordinates",
                result.resultPoints.any { p -> kotlin.math.abs(p.x-x)<=2f&&kotlin.math.abs(p.y-y)<=2f }) }
            val old=pixels;val oldWidth=width;val oldHeight=height
            pixels=IntArray(width*height) { index -> old[(oldHeight-1-index%oldHeight)*oldWidth+index/oldHeight] }
            expected=expected.map { (x,y) -> oldHeight-y to x }
            width=oldHeight;height=oldWidth
        }
    }
    @Test fun recoveryIsThrottledAndInvalidFramesDoNotBecomeInvitations() {
        val raw=javaClass.getResourceAsStream("/qr-regression.txt")!!.bufferedReader().use { it.readText().trim() }
        val matrix=QRCodeWriter().encode(raw,BarcodeFormat.QR_CODE,800,800)
        val source=RGBLuminanceSource(800,800,IntArray(800*800) { if(matrix[it%800,it/800])0xFF000000.toInt() else -1 })
        var now=1000L
        val reader=com.google.zxing.MultiFormatReader()
        val decoder=InstantQrDecoder(reader){now}
        reader.setHints(InstantQr.decodeHints+mapOf(com.google.zxing.DecodeHintType.NEED_RESULT_POINT_CALLBACK to decoder))
        assertEquals(raw,decoder.decode(source)?.text)
        assertNull("Do not repeat expensive recovery within 500ms",decoder.decode(source))
        now+=500;assertEquals(raw,decoder.decode(source)?.text)
        val blank=RGBLuminanceSource(800,800,IntArray(800*800){-1})
        repeat(3) { assertNull(decoder.decode(blank)) }
        assertFalse(InstantQr.decodeHints.containsKey(com.google.zxing.DecodeHintType.PURE_BARCODE))
    }
}
