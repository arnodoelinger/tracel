package com.tracel.tests.log

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.engine.container.memory.InMemoryContainerSlotLog
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InMemoryContainerSlotLogTest {
    @Test
    fun `layoutAt picks the newest entry at or before the instant asked`() = runTest {
        val log = InMemoryContainerSlotLog()
        val holder = block(1, 70, 1)
        val sorted = listOf(ContainerSlotEntry(0, diamond, 1))
        val rearranged = listOf(ContainerSlotEntry(5, diamond, 1))
        log.record(holder, epochMillis = 1_000, sorted)
        log.record(holder, epochMillis = 2_000, rearranged)

        assertEquals(sorted, log.layoutAt(holder, asOfMillis = 1_500))
        assertEquals(rearranged, log.layoutAt(holder, asOfMillis = 2_000))
        assertEquals(rearranged, log.layoutAt(holder, asOfMillis = 9_999))
    }

    @Test
    fun `nothing old enough, or nothing at all, answers null`() = runTest {
        val log = InMemoryContainerSlotLog()
        val holder = block(2, 70, 2)
        assertNull(log.layoutAt(holder, asOfMillis = 1_000))

        log.record(holder, epochMillis = 5_000, listOf(ContainerSlotEntry(0, diamond, 1)))
        assertNull(log.layoutAt(holder, asOfMillis = 1_000))
    }

    @Test
    fun `a limit that runs out before an old enough entry answers null too`() = runTest {
        val log = InMemoryContainerSlotLog()
        val holder = block(3, 70, 3)
        log.record(holder, epochMillis = 1_000, listOf(ContainerSlotEntry(0, diamond, 1)))
        log.record(holder, epochMillis = 2_000, listOf(ContainerSlotEntry(1, diamond, 1)))
        log.record(holder, epochMillis = 3_000, listOf(ContainerSlotEntry(2, diamond, 1)))

        assertNull(log.layoutAt(holder, asOfMillis = 1_000, limit = 2), "the 1000 entry is the third-newest")
        assertEquals(
            listOf(ContainerSlotEntry(0, diamond, 1)),
            log.layoutAt(holder, asOfMillis = 1_000, limit = 3),
        )
    }
}
