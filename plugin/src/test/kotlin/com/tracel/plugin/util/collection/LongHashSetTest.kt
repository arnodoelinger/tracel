package com.tracel.plugin.util.collection

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LongHashSetTest {
    @Test
    fun `holds what a hash set of longs holds, through growing`() {
        val random = Random(3)
        val set = LongHashSet()
        val reference = HashSet<Long>()
        repeat(100_000) {
            val value = random.nextLong(-50_000, 50_000) * 1_000_003L
            assertEquals(reference.add(value), set.add(value))
        }
        assertEquals(reference.size, set.size)
        repeat(100_000) {
            val value = random.nextLong(-50_000, 50_000) * 1_000_003L
            assertEquals(value in reference, value in set)
        }
    }

    @Test
    fun `zero and the extremes are ordinary members`() {
        val set = LongHashSet()
        assertFalse(0L in set)
        assertTrue(set.add(0L))
        assertTrue(set.add(Long.MIN_VALUE))
        assertTrue(set.add(Long.MAX_VALUE))
        assertFalse(set.add(0L))
        assertTrue(0L in set && Long.MIN_VALUE in set && Long.MAX_VALUE in set)
        assertEquals(3, set.size)
    }
}
