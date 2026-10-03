package com.tracel.plugin.rollback.structure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TickGovernorTest {
    private val settings = GovernorSettings(minNanos = 4_000_000L, maxNanos = 30_000_000L)

    private fun millis(mspt: Double?, own: Double = 0.0) = allowance(settings, mspt, own) / 1_000_000.0

    @Test
    fun `an idle region gets the ceiling`() {
        assertEquals(30.0, millis(mspt = 3.0))
    }

    @Test
    fun `a busy region gets most of what is left of the tick`() {
        assertEquals(8.0, millis(mspt = 40.0), 1e-9)
    }

    @Test
    fun `the restore's own time is not counted as someone else's load`() {
        assertEquals(30.0, millis(mspt = 33.0, own = 30.0))
        assertEquals(13.6, millis(mspt = 33.0, own = 0.0), 1e-9)
    }

    @Test
    fun `a region over its tick gets the floor`() {
        assertEquals(4.0, millis(mspt = 60.0))
    }

    @Test
    fun `no reading means a modest slice, not zero and not the ceiling`() {
        assertEquals(5.0, millis(mspt = null))
        assertEquals(5.0, millis(mspt = Double.NaN))
    }

    @Test
    fun `the slice always stays inside the configured bounds`() {
        for (mspt in listOf(0.0, 1.0, 25.0, 49.0, 50.0, 500.0)) {
            for (own in listOf(0.0, 10.0, 1000.0)) {
                val got = allowance(settings, mspt, own)
                assertTrue(got in settings.minNanos..settings.maxNanos) { "$mspt/$own gave $got" }
            }
        }
    }
}
