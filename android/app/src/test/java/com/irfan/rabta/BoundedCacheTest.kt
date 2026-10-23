package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

class BoundedCacheTest {
    @Test fun readsKeepRecentEntriesAndCountIsBounded() {
        val c=BoundedCache<String,String>(2,100) {it.length.toLong()}
        c["a"]="one";c["b"]="two";assertEquals("one",c["a"]);c["c"]="three"
        assertNull(c["b"]);assertEquals("one",c["a"]);assertEquals("three",c["c"])
    }
    @Test fun byteBudgetReplacementOversizeAndClear() {
        val c=BoundedCache<String,String>(10,5) {it.length.toLong()}
        c["a"]="123";c["b"]="456";assertNull(c["a"])
        c["b"]="1";c["c"]="2345";assertEquals("1",c["b"])
        c["c"]="123456";assertNull(c["c"]);assertEquals("1",c["b"])
        c.clear();assertNull(c["b"])
    }
}
