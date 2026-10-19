package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

/** One account per phone: the server only ever sees this hash, never the phone's Android ID. */
class PhoneIdTest {
    @Test fun hashIsStableHexAndHidesTheId() {
        val a = PhoneId.hash("9f2c1e4b7a6d5c3e")
        assertEquals(64, a.length); assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("Same phone, same hash", a, PhoneId.hash("9f2c1e4b7a6d5c3e"))
        assertNotEquals("Another phone, another hash", a, PhoneId.hash("9f2c1e4b7a6d5c3f"))
        assertFalse(a.contains("9f2c1e4b7a6d5c3e"))
    }
}
