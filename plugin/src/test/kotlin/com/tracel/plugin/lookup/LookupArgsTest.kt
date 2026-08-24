package com.tracel.plugin.lookup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LookupArgsTest {
    private val now = 1_000_000L

    @Test
    fun `user, item and action flags parse verbatim`() {
        val parsed = parseLookupArgs(listOf("user:Steve,Griefer", "-user:Notch", "item:minecraft:diamond", "action:explosion"), now)

        assertEquals(setOf("Steve", "Griefer"), parsed.users)
        assertEquals(setOf("Notch"), parsed.excludedUsers)
        assertEquals("minecraft:diamond", parsed.item)
        assertEquals(setOf("explosion"), parsed.actions)
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun `time with a single duration means since that long ago`() {
        val parsed = parseLookupArgs(listOf("time:10m"), now)
        assertEquals(now - 600_000L, parsed.since)
        assertEquals(null, parsed.until)
    }

    @Test
    fun `time with a range means between the two durations ago, order-independent`() {
        val a = parseLookupArgs(listOf("time:1d..2d"), now)
        assertEquals(now - 172_800_000L, a.since)
        assertEquals(now - 86_400_000L, a.until)

        val b = parseLookupArgs(listOf("time:2d..1d"), now)
        assertEquals(a.since, b.since)
        assertEquals(a.until, b.until)
    }

    @Test
    fun `time accepts a bare date for the whole day, and a date-to-date range`() {
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.of(2026, 8, 20)
        val startOfDay = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfNextDay = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val single = parseLookupArgs(listOf("time:2026-08-20"), now)
        assertEquals(startOfDay, single.since)
        assertEquals(startOfNextDay, single.until)

        val range = parseLookupArgs(listOf("time:2026-08-20..2026-08-21"), now)
        assertEquals(startOfDay, range.since)
        assertEquals(startOfNextDay.plus(86_400_000L), range.until)
    }

    @Test
    fun `time accepts today and yesterday relative to now, not the system clock`() {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfYesterday = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val todayParsed = parseLookupArgs(listOf("time:today"), now)
        assertEquals(startOfToday, todayParsed.since)
        assertEquals(null, todayParsed.until)

        val yesterdayParsed = parseLookupArgs(listOf("time:yesterday"), now)
        assertEquals(startOfYesterday, yesterdayParsed.since)
        assertEquals(startOfToday, yesterdayParsed.until)
    }

    @Test
    fun `time mixes a duration-ago with an absolute date-time in a range`() {
        val zone = java.time.ZoneId.systemDefault()
        val until = java.time.LocalDateTime.parse("2026-08-20T18:00").atZone(zone).toInstant().toEpochMilli()

        val parsed = parseLookupArgs(listOf("time:2h..2026-08-20T18:00"), now)
        assertEquals(minOf(now - 7_200_000L, until), parsed.since)
        assertEquals(maxOf(now - 7_200_000L, until), parsed.until)
    }

    @Test
    fun `before and after are independent absolute-ish bounds`() {
        val parsed = parseLookupArgs(listOf("before:1h", "after:2h"), now)
        assertEquals(now - 3_600_000L, parsed.until)
        assertEquals(now - 7_200_000L, parsed.since)
    }

    @Test
    fun `scope parses a block radius, a chunk radius, the current chunk, or a world name`() {
        assertEquals(LookupScope.Blocks(30), parseLookupArgs(listOf("scope:30"), now).scope)
        assertEquals(LookupScope.Chunks(5), parseLookupArgs(listOf("scope:5c"), now).scope)
        assertEquals(LookupScope.CurrentChunk, parseLookupArgs(listOf("scope:chunk"), now).scope)
        assertEquals(LookupScope.World("survival"), parseLookupArgs(listOf("scope:survival"), now).scope)
    }

    @Test
    fun `scope_horizontal_only flag is captured`() {
        val parsed = parseLookupArgs(listOf("scope:30", "#scope_horizontal_only"), now)
        assertTrue(parsed.horizontalOnly)
    }

    @Test
    fun `a blank scope value is reported as an error`() {
        val parsed = parseLookupArgs(listOf("scope:"), now)
        assertEquals(listOf("invalid scope: scope:"), parsed.errors)
    }

    @Test
    fun `count flags and limit parse`() {
        val parsed = parseLookupArgs(listOf("#count", "#count-only", "limit:5"), now)
        assertTrue(parsed.count)
        assertTrue(parsed.countOnly)
        assertEquals(5, parsed.limit)
    }

    @Test
    fun `unrecognized or malformed flags are reported, not silently dropped`() {
        val parsed = parseLookupArgs(listOf("bogus:flag", "scope:", "time:notaduration"), now)
        assertEquals(3, parsed.errors.size)
    }

    @Test
    fun `an empty or unrecognized-prefix token suggests flag prefixes`() {
        val suggestions = suggestLookupToken("", listOf("Steve"), listOf("world"), listOf("hopper"))
        assertTrue("user:" in suggestions)
        assertTrue("scope:" in suggestions)
        assertTrue("#count" in suggestions)
    }

    @Test
    fun `user-prefixed tokens suggest live names, filtered by what's already typed`() {
        assertEquals(listOf("user:Steve"), suggestLookupToken("user:S", listOf("Steve", "Griefer"), emptyList(), emptyList()))
        assertEquals(listOf("-user:Griefer"), suggestLookupToken("-user:G", listOf("Steve", "Griefer"), emptyList(), emptyList()))
    }

    @Test
    fun `scope-prefixed token suggests chunk and world names, filtered by what's already typed`() {
        assertEquals(
            listOf("scope:overworld"),
            suggestLookupToken("scope:o", emptyList(), listOf("overworld", "nether"), emptyList()),
        )
    }

    @Test
    fun `action-prefixed token suggests cause names`() {
        assertEquals(listOf("action:hopper"), suggestLookupToken("action:h", emptyList(), emptyList(), listOf("hopper", "explosion")))
    }
}
