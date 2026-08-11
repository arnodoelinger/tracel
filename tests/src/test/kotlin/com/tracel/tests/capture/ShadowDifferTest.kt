package com.tracel.tests.capture

import com.tracel.engine.capture.ShadowDiffer
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The mechanism the whole capture design leans on: what moved is derived from what an
 * inventory contains now versus what it contained last time, never from interpreting the
 * Bukkit event that caused it.
 */
class ShadowDifferTest {
    @Test
    fun `the first snapshot of a holder produces gains, not deltas from nothing`() {
        val differ = ShadowDiffer()
        val chest = block(0, 64, 0)

        val deltas = differ.diff(chest, mapOf(diamond to 10L))

        assertEquals(1, deltas.size)
        assertEquals(10L, deltas.single().delta)
        assertEquals(diamond, deltas.single().itemKey)
    }

    @Test
    fun `an unchanged inventory produces no deltas at all`() {
        val differ = ShadowDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))

        val deltas = differ.diff(chest, mapOf(diamond to 10L))

        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `a partial withdrawal produces exactly one negative delta`() {
        val differ = ShadowDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))

        val deltas = differ.diff(chest, mapOf(diamond to 6L))

        assertEquals(1, deltas.size)
        assertEquals(-4L, deltas.single().delta)
    }

    @Test
    fun `an item key disappearing entirely still produces its delta`() {
        val differ = ShadowDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))

        // The new snapshot does not even mention diamond anymore — the delta must still show up
        val deltas = differ.diff(chest, emptyMap())

        assertEquals(1, deltas.size)
        assertEquals(-10L, deltas.single().delta)
    }

    @Test
    fun `multiple item keys changing at once each produce their own delta`() {
        val differ = ShadowDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L, diamondBlock to 2L))

        val deltas = differ.diff(chest, mapOf(diamond to 4L, diamondBlock to 3L))

        val byKey = deltas.associate { it.itemKey to it.delta }
        assertEquals(mapOf(diamond to -6L, diamondBlock to 1L), byKey)
    }

    @Test
    fun `different holders never see each other's deltas`() {
        val differ = ShadowDiffer()
        val chestA = block(0, 64, 0)
        val chestB = block(10, 64, 0)
        differ.diff(chestA, mapOf(diamond to 10L))

        val deltasB = differ.diff(chestB, mapOf(diamond to 5L))

        assertEquals(5L, deltasB.single().delta, "B's first snapshot, unaffected by A's baseline")
    }

    @Test
    fun `forgetting a holder resets it to a fresh baseline`() {
        val differ = ShadowDiffer()
        val chest = block(0, 64, 0)
        differ.diff(chest, mapOf(diamond to 10L))

        differ.forget(chest)
        val deltas = differ.diff(chest, mapOf(diamond to 10L))

        assertEquals(10L, deltas.single().delta, "forgotten, so this reads as a fresh gain, not 'no change'")
    }
}
