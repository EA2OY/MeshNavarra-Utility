/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Navadmin channel slot-picking logic: slot 0 (primary) is never
 * offered, the first free secondary slot (1-7) is used, and -1 is returned when
 * all 8 slots are occupied.
 */
class ChannelSlotTest {

    @Test
    fun onlyPrimaryUsed_returnsSlot1() {
        assertEquals(1, firstFreeChannelSlotFor(setOf(0)))
    }

    @Test
    fun firstFreeSecondarySlotIsUsed() {
        assertEquals(3, firstFreeChannelSlotFor(setOf(0, 1, 2)))
    }

    @Test
    fun freeSlotInsideIsPreferred() {
        // Slots 0,2,4 used -> 1 is the first free secondary slot.
        assertEquals(1, firstFreeChannelSlotFor(setOf(0, 2, 4)))
    }

    @Test
    fun allSlotsUsed_returnsMinusOne() {
        assertEquals(-1, firstFreeChannelSlotFor(setOf(0, 1, 2, 3, 4, 5, 6, 7)))
    }

    @Test
    fun empty_returnsSlot1() {
        assertEquals(1, firstFreeChannelSlotFor(emptySet()))
    }
}
