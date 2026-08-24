package com.tracel.tests.capture

import com.tracel.engine.capture.SnapshotDiffer
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The mechanism the whole capture design leans on: what moved is derived from what an
 * inventory contains now versus what it contained last time, never from interpreting the
 * `Bukkit` event that caused it.
 */
class SnapshotDifferTest {
    @Test
    fun `the first snapshot of a holder produces gains, not deltas from nothing`() = runTest {
        val differ = SnapshotDiffer()
        val chest = block(0, 64, 0)

        val deltas = differ.diff(chest, mapOf(diamond to 10L))

        assertEquals(1, deltas.size)
        assertEquals(10L, deltas.single().delta)
        assertEquals(diamond, deltas.single().itemKey)
    }

    @Test
    fun `an unchanged inventory produces no deltas at all`() = runTest {
        val differ = SnapshotDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))

        val deltas = differ.diff(chest, mapOf(diamond to 10L))

        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `a partial withdrawal produces exactly one negative delta`() = runTest {
        val differ = SnapshotDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))

        val deltas = differ.diff(chest, mapOf(diamond to 6L))

        assertEquals(1, deltas.size)
        assertEquals(-4L, deltas.single().delta)
    }

    @Test
    fun `an item key disappearing entirely still produces its delta`() = runTest {
        val differ = SnapshotDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))

        // The new snapshot does not even mention diamond anymore — the delta must still show up
        val deltas = differ.diff(chest, emptyMap())

        assertEquals(1, deltas.size)
        assertEquals(-10L, deltas.single().delta)
    }

    @Test
    fun `multiple item keys changing at once each produce their own delta`() = runTest {
        val differ = SnapshotDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L, diamondBlock to 2L))

        val deltas = differ.diff(chest, mapOf(diamond to 4L, diamondBlock to 3L))

        val byKey = deltas.associate { it.itemKey to it.delta }
        assertEquals(mapOf(diamond to -6L, diamondBlock to 1L), byKey)
    }

    @Test
    fun `different holders never see each other's deltas`() = runTest {
        val differ = SnapshotDiffer()
        val chestA = block(0, 64, 0)
        val chestB = block(10, 64, 0)
        differ.diff(chestA, mapOf(diamond to 10L))

        val deltasB = differ.diff(chestB, mapOf(diamond to 5L))

        assertEquals(5L, deltasB.single().delta, "B's first snapshot, unaffected by A's baseline")
    }

    @Test
    fun `forgetting a holder resets it to a fresh baseline`() = runTest {
        val differ = SnapshotDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))

        differ.forget(chest)
        val deltas = differ.diff(chest, mapOf(diamond to 10L))

        assertEquals(10L, deltas.single().delta, "forgotten, so this reads as a fresh gain, not 'no change'")
    }

    @Test
    fun `adjust keeps a later diff from seeing a change the adjuster already accounted for`() = runTest {
        val differ = SnapshotDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))
        differ.adjust(chest, diamond, -4L)

        val deltas = differ.diff(chest, mapOf(diamond to 6L))
        assertTrue(deltas.isEmpty(), "the snapshot should already read after the adjustment")
    }

    @Test
    fun `adjust accumulates across multiple calls before the next diff`() = runTest {
        val differ = SnapshotDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))

        differ.adjust(chest, diamond, -4L)
        differ.adjust(chest, diamond, 4L)

        val deltas = differ.diff(chest, mapOf(diamond to 10L))
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `adjust down to exactly zero drops the item key instead of leaving a zero entry`() = runTest {
        val differ = SnapshotDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))

        differ.adjust(chest, diamond, -10L)

        val deltas = differ.diff(chest, mapOf(diamond to 3L))
        assertEquals(3L, deltas.single().delta)
    }

    @Test
    fun `adjust on a holder with no prior snapshot still works, starting from empty`() = runTest {
        val differ = SnapshotDiffer()
        val groundItem = block(0, 64, 0)

        differ.adjust(groundItem, diamond, 5L)

        val deltas = differ.diff(groundItem, mapOf(diamond to 5L))
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `the first sight of a holder is measured against the ledger, not against nothing`() = runTest {
        val chest = block(0, 64, 0)
        val differ = SnapshotDiffer { mapOf(diamond to 2L) }

        val deltas = differ.diff(chest, emptyMap())

        assertEquals(1, deltas.size)
        assertEquals(-2L, deltas.single().delta, "material the ledger still believed in has to be reported as lost")
        assertTrue(deltas.single().fromGap, "nothing witnessed this loss — it must not be typed as an ordinary one")
    }

    @Test
    fun `a first sight reports both directions of the gap in one diff`() = runTest {
        val chest = block(0, 64, 0)
        val differ = SnapshotDiffer { mapOf(diamond to 2L) }

        val deltas = differ.diff(chest, mapOf(diamondBlock to 64L)).associateBy { it.itemKey }

        assertEquals(-2L, deltas.getValue(diamond).delta)
        assertEquals(64L, deltas.getValue(diamondBlock).delta)
        assertTrue(deltas.values.all { it.fromGap })
    }

    @Test
    fun `the ledger is consulted once, not on every diff`() = runTest {
        val chest = block(0, 64, 0)
        var reads = 0
        val differ = SnapshotDiffer { reads++; mapOf(diamond to 2L) }

        differ.diff(chest, mapOf(diamond to 2L))
        val deltas = differ.diff(chest, mapOf(diamond to 5L))

        assertEquals(1, reads, "a seeded holder has a live snapshot — going back to storage would be pure cost")
        assertEquals(3L, deltas.single().delta)
        assertTrue(!deltas.single().fromGap, "this one was witnessed live and must be typed as such")
    }

    @Test
    fun `an adjust booked before the first diff is folded into the ledger baseline`() = runTest {
        val player = block(0, 64, 0)
        val differ = SnapshotDiffer { mapOf(diamond to 10L) }

        differ.adjust(player, diamond, -4L)

        val deltas = differ.diff(player, mapOf(diamond to 6L))
        assertTrue(deltas.isEmpty(), "10 believed, 4 dropped, 6 held")
    }

    @Test
    fun `rebaseline adopts the contents silently, without consulting the ledger`() = runTest {
        val chest = block(0, 64, 0)
        var reads = 0
        val differ = SnapshotDiffer { reads++; mapOf(diamond to 2L) }

        differ.rebaseline(chest, mapOf(diamond to 7L))

        assertEquals(0, reads, "Tracel just wrote these contents itself, there is no gap")
        assertTrue(differ.diff(chest, mapOf(diamond to 7L)).isEmpty())
    }
}
