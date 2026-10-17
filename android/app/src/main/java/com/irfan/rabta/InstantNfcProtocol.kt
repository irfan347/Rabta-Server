package com.irfan.rabta

/** Read-only, bounded APDUs for public pairing records. No key operations or pairing approval. */
class InstantNfcProtocol(private val current:()->ByteArray?) {
    companion object {
        const val AID="F0524142544101"
        const val CHUNK=200
        val select:ByteArray get()=byteArrayOf(0,0xA4.toByte(),4,0,7)+AID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()+byteArrayOf(0)
        val size:ByteArray get()=byteArrayOf(0,0xCA.toByte(),0,0,0)
        fun read(offset:Int,length:Int):ByteArray {
            require(offset in 0..InstantInvitation.MAX_TEXT&&length in 1..CHUNK)
            return byteArrayOf(0,0xB0.toByte(),(offset shr 8).toByte(),offset.toByte(),length.toByte())
        }
        fun payload(response:ByteArray):ByteArray {
            require(response.size>=2&&response[response.size-2]==0x90.toByte()&&response.last()==0.toByte()){"The other phone is not sharing an active Instant Connect code. Keep its code screen open and retry"}
            return response.copyOf(response.size-2)
        }
    }
    private var selected:ByteArray?=null
    @Synchronized fun reset() { selected=null }
    @Synchronized fun respond(command:ByteArray):ByteArray {
        fun status(high:Int,low:Int)=byteArrayOf(high.toByte(),low.toByte())
        fun ok(data:ByteArray)=data+status(0x90,0)
        val live=current()?.takeIf { it.size in 100..InstantInvitation.MAX_TEXT }
        if(command.contentEquals(select)) {
            selected=live?.copyOf()
            return if(selected!=null)ok(byteArrayOf())else status(0x6A,0x82)
        }
        val source=selected
        if(source==null||live==null||!source.contentEquals(live)){selected=null;return status(0x69,0x85)}
        if(command.contentEquals(size))return ok(byteArrayOf((source.size shr 8).toByte(),source.size.toByte()))
        if(command.size==5&&command[0]==0.toByte()&&command[1]==0xB0.toByte()) {
            val offset=((command[2].toInt()and 255)shl 8)or(command[3].toInt()and 255)
            val length=command[4].toInt()and 255
            if(length !in 1..CHUNK||offset !in source.indices||offset+length>source.size)return status(0x6B,0)
            return ok(source.copyOfRange(offset,offset+length))
        }
        return status(0x6D,0)
    }
}
