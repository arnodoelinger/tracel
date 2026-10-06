package com.tracel.plugin.metrics

import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.scope.LookupScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryTest {
    @Test
    fun `a drained chart starts over`() {
        val chart = "test_drain"
        repeat(3) { Telemetry.count(chart, "a") }
        Telemetry.count(chart, "b")
        assertEquals(mapOf("a" to 3, "b" to 1), Telemetry.drain(chart))
        assertTrue(Telemetry.drain(chart).isEmpty())
    }

    @Test
    fun `a chart nobody drains stops taking new names`() {
        val chart = "test_cap"
        repeat(Telemetry.MAX_KEYS + 10) { Telemetry.count(chart, "key$it") }
        val drained = Telemetry.drain(chart)
        assertEquals(Telemetry.MAX_KEYS + 1, drained.size)
        assertEquals(10, drained[Telemetry.OTHER])
    }

    @Test
    fun `flags are counted by name, and a bare command still shows up`() {
        val chart = "test_flags"
        Telemetry.flags(chart, ParsedLookupArgs(users = setOf("Steve"), since = 1L, scope = LookupScope.Blocks(10)))
        Telemetry.flags(chart, ParsedLookupArgs())
        assertEquals(mapOf("user" to 1, "time" to 1, "radius" to 1, "none" to 1), Telemetry.drain(chart))
    }

    @Test
    fun `an error is named by its class and where in Tracel it came from`() {
        Telemetry.drain(Telemetry.ERRORS)
        Telemetry.error(IllegalStateException("secret server detail"))
        assertEquals(setOf("IllegalStateException in TelemetryTest"), Telemetry.drain(Telemetry.ERRORS).keys)
    }

    @Test
    fun `a warning keeps its kind and drops what it was about`() {
        Telemetry.drain(Telemetry.WARNINGS)
        Telemetry.warning("unbooked-cargo:CHEST_MINECART")
        assertEquals(setOf("unbooked-cargo"), Telemetry.drain(Telemetry.WARNINGS).keys)
    }

    @Test
    fun `a rollback's reach lands in a bucket`() {
        assertEquals("9-32", Telemetry.radiusBucket(32))
        assertEquals("257+", Telemetry.radiusBucket(Int.MAX_VALUE))
        assertEquals("1d", Telemetry.windowBucket(2 * 3_600_000L))
        assertEquals("90d+", Telemetry.windowBucket(Long.MAX_VALUE))
    }
}
