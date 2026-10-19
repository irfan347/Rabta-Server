package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

/** Who sees a status: all contacts, all except some, or only some — always limited to current contacts. */
class StatusAudienceTest {
    private val contacts = listOf("A1", "B2", "C3", "D4")
    @Test fun myContactsIsEveryone() = assertEquals(contacts, StatusPost.resolve(StatusPost.Audience.ALL, contacts, setOf("B2"), setOf("C3")))
    @Test fun exceptLeavesThemOut() = assertEquals(listOf("A1", "C3"), StatusPost.resolve(StatusPost.Audience.EXCEPT, contacts, setOf("B2", "D4"), emptySet()))
    @Test fun onlyKeepsJustThem() = assertEquals(listOf("C3"), StatusPost.resolve(StatusPost.Audience.ONLY, contacts, emptySet(), setOf("C3", "Z9")))
    @Test fun nobodyChosenMeansNobody() = assertTrue(StatusPost.resolve(StatusPost.Audience.ONLY, contacts, emptySet(), emptySet()).isEmpty())
}
