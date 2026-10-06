package com.tracel.storage.ports.container

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ContainerSlotLogTest {
    @Test
    fun `a layout round-trips every field`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val holder = block(1, 70, 1)
            val slots = listOf(ContainerSlotEntry(0, diamond, 64), ContainerSlotEntry(13, diamondBlock, 3))
            stack.containerSlots.record(holder, epochMillis = 1_000, slots)

            assertEquals(slots, stack.containerSlots.layoutAt(holder, asOfMillis = 1_000))
        }
    }

    @Test
    fun `layoutAt picks the newest entry at or before the instant asked`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val holder = block(2, 70, 2)
            val sorted = listOf(ContainerSlotEntry(0, diamond, 1))
            val rearranged = listOf(ContainerSlotEntry(5, diamond, 1))
            stack.containerSlots.record(holder, epochMillis = 1_000, sorted)
            stack.containerSlots.record(holder, epochMillis = 2_000, rearranged)

            assertEquals(sorted, stack.containerSlots.layoutAt(holder, asOfMillis = 1_500), "before the rearrange")
            assertEquals(rearranged, stack.containerSlots.layoutAt(holder, asOfMillis = 2_000), "at the rearrange")
            assertEquals(rearranged, stack.containerSlots.layoutAt(holder, asOfMillis = 9_999), "well after it")
        }
    }

    @Test
    fun `nothing old enough, or nothing at all, answers null`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val holder = block(3, 70, 3)
            assertNull(stack.containerSlots.layoutAt(holder, asOfMillis = 1_000), "no history at all")

            stack.containerSlots.record(holder, epochMillis = 5_000, listOf(ContainerSlotEntry(0, diamond, 1)))
            assertNull(stack.containerSlots.layoutAt(holder, asOfMillis = 1_000), "only newer history exists")
        }
    }

    @Test
    fun `a container that emptied out still answers with an empty layout, not the one before it`(@TempDir dir: Path) =
        runTest {
            Stack(dir).use { stack ->
                val holder = block(4, 70, 4)
                stack.containerSlots.record(holder, epochMillis = 1_000, listOf(ContainerSlotEntry(0, diamond, 1)))
                stack.containerSlots.record(holder, epochMillis = 2_000, emptyList())

                assertEquals(emptyList<ContainerSlotEntry>(), stack.containerSlots.layoutAt(holder, asOfMillis = 2_000))
            }
        }

    @Test
    fun `a different container's history never answers for this one`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val holder = block(5, 70, 5)
            val other = block(6, 70, 5)
            stack.containerSlots.record(other, epochMillis = 1_000, listOf(ContainerSlotEntry(0, diamond, 1)))

            assertNull(stack.containerSlots.layoutAt(holder, asOfMillis = 1_000))
        }
    }
}
