package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

class InstantNfcProtocolTest {
    @Test fun boundedChunksReconstructExactlyTheSelectedCode() {
        val data=("rabta:ic:1:"+"a".repeat(1200)).toByteArray()
        val protocol=InstantNfcProtocol { data }
        assertTrue(InstantNfcProtocol.payload(protocol.respond(InstantNfcProtocol.select)).isEmpty())
        val size=InstantNfcProtocol.payload(protocol.respond(InstantNfcProtocol.size))
        val length=((size[0].toInt()and 255)shl 8)or(size[1].toInt()and 255);assertEquals(data.size,length)
        val result=java.io.ByteArrayOutputStream()
        while(result.size()<length) {
            val chunk=minOf(InstantNfcProtocol.CHUNK,length-result.size())
            val response=protocol.respond(InstantNfcProtocol.read(result.size(),chunk))
            assertTrue(response.size<=InstantNfcProtocol.CHUNK+2)
            result.write(InstantNfcProtocol.payload(response))
        }
        assertArrayEquals(data,result.toByteArray())
    }
    @Test fun closedExpiredOrChangedShareCannotContinueAnOldTransfer() {
        var current:ByteArray?="x".repeat(1000).toByteArray()
        val protocol=InstantNfcProtocol { current }
        protocol.respond(InstantNfcProtocol.select)
        current="y".repeat(1000).toByteArray()
        assertTrue(runCatching { InstantNfcProtocol.payload(protocol.respond(InstantNfcProtocol.read(0,100))) }.isFailure)
        current=null
        assertTrue(runCatching { InstantNfcProtocol.payload(protocol.respond(InstantNfcProtocol.select)) }.isFailure)
    }
    @Test fun noWritesOrOutOfBoundsReadsAndSelectionIsRequired() {
        val data="x".repeat(500).toByteArray();val protocol=InstantNfcProtocol { data }
        assertTrue(runCatching { InstantNfcProtocol.payload(protocol.respond(InstantNfcProtocol.size)) }.isFailure)
        protocol.respond(InstantNfcProtocol.select)
        for(command in listOf(InstantNfcProtocol.read(490,20),byteArrayOf(0,0xB0.toByte(),0,0,0),byteArrayOf(0,0xD6.toByte(),0,0,1,42))) {
            assertTrue(runCatching { InstantNfcProtocol.payload(protocol.respond(command)) }.isFailure)
        }
        assertArrayEquals("x".repeat(500).toByteArray(),data)
        protocol.reset();assertTrue(runCatching { InstantNfcProtocol.payload(protocol.respond(InstantNfcProtocol.size)) }.isFailure)
    }
    @Test fun oversizedOrMissingCodesNeverLeaveTheService() {
        for(data in listOf(null,ByteArray(99),ByteArray(InstantInvitation.MAX_TEXT+1))) {
            val protocol=InstantNfcProtocol { data }
            assertTrue(runCatching { InstantNfcProtocol.payload(protocol.respond(InstantNfcProtocol.select)) }.isFailure)
        }
    }
}
