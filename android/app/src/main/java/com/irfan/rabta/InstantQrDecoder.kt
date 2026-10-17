package com.irfan.rabta

import com.google.zxing.*
import com.journeyapps.barcodescanner.Decoder
import com.journeyapps.barcodescanner.DecoderFactory

/** Retains the existing camera pipeline; normal QR detection gets bounded orientation recovery. */
class InstantQrDecoderFactory:DecoderFactory {
    override fun createDecoder(baseHints:MutableMap<DecodeHintType,*>?):Decoder {
        val reader=MultiFormatReader()
        val decoder=InstantQrDecoder(reader)
        val hints=mutableMapOf<DecodeHintType,Any>()
        baseHints?.forEach { (key,value) -> if(value!=null)hints[key]=value }
        hints.putAll(InstantQr.decodeHints)
        // Decoder owns candidate points; BarcodeView still obtains them through getPossibleResultPoints().
        hints[DecodeHintType.NEED_RESULT_POINT_CALLBACK]=decoder
        reader.setHints(hints)
        return decoder
    }
}

class InstantQrDecoder(reader:MultiFormatReader,private val clock:()->Long={System.nanoTime()/1000000}):Decoder(reader) {
    private var lastRecovery:Long?=null
    private var turns=0
    private var originalWidth=0
    private var originalHeight=0

    override fun decode(source:LuminanceSource):Result? {
        turns=0;originalWidth=source.width;originalHeight=source.height
        super.decode(source)?.let { return it }
        // Blank/non-QR frames stay on the existing fast path. Bound allocations and repeated work.
        if(super.getPossibleResultPoints().size<2||source.width.toLong()*source.height>1000000)return null
        val now=clock()
        if(lastRecovery?.let { now-it<500 }==true)return null
        lastRecovery=now
        return try {
            var rotated=source
            for(turn in 1..3) {
                rotated=clockwise(rotated);turns=turn
                val result=super.decode(rotated)?:continue
                val mapped=result.resultPoints?.map { original(it) }?.toTypedArray()
                val recovered=Result(result.text,result.rawBytes,result.numBits,mapped,result.barcodeFormat,result.timestamp)
                result.resultMetadata?.let { recovered.putAllMetadata(it) }
                return recovered
            }
            null
        }catch(_:Exception) { null } // Same no-throw frame contract as the existing JourneyApps Decoder.
    }

    override fun getPossibleResultPoints():MutableList<ResultPoint> =
        super.getPossibleResultPoints().map { original(it) }.toMutableList()

    private fun original(point:ResultPoint)=when(turns) {
        1 -> ResultPoint(point.y,originalHeight-point.x)
        2 -> ResultPoint(originalWidth-point.x,originalHeight-point.y)
        3 -> ResultPoint(originalWidth-point.y,point.x)
        else -> point
    }

    private fun clockwise(source:LuminanceSource):LuminanceSource {
        val width=source.width;val height=source.height;val old=source.matrix
        val pixels=ByteArray(width*height)
        for(y in 0 until height)for(x in 0 until width)pixels[x*height+height-1-y]=old[y*width+x]
        return PlanarYUVLuminanceSource(pixels,height,width,0,0,height,width,false)
    }
}
