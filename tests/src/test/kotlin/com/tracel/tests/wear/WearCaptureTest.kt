package com.tracel.tests.wear

import com.tracel.engine.capture.material.WearCapture
import com.tracel.engine.wear.WearMark
import com.tracel.engine.wear.damageAt
import com.tracel.engine.wear.memory.InMemoryWearLog
import com.tracel.model.cause.CauseKind
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WearCaptureTest {
    private val pickaxe = ItemKey("minecraft:golden_pickaxe")

    @Test
    fun `damage at a moment is what the last mark by then left, or where the first later one started`() {
        val lot = LotId(1)
        val marks = listOf(WearMark(lot, 100, 0, 3), WearMark(lot, 200, 3, 7))

        assertEquals(0, marks.damageAt(50))
        assertEquals(3, marks.damageAt(150))
        assertEquals(7, marks.damageAt(200))
        assertNull(emptyList<WearMark>().damageAt(150))
    }

    @Test
    fun `wear is pinned to the tool's lot and moves nothing`() = runTest {
        val world = LedgerHarness()
        val wear = InMemoryWearLog()
        val capture = WearCapture(world.repo, world.log, wear, world::nextTxn, world::nextSeq)
        val steve = player(1)
        val tool = world.ledger.mint(steve, pickaxe, Quantity(1), world.nextTxn())

        val row = capture.record(steve, pickaxe, 3, 4, 1_000, at = null)

        assertEquals(CauseKind.WEAR, row?.cause)
        assertEquals(listOf(tool.id), row?.lots?.map { it.lotId })
        assertEquals(listOf(WearMark(tool.id, 1_000, 3, 4)), wear.marksOf(listOf(tool.id))[tool.id])
        assertEquals(steve, world.ledger.currentHolderOf(tool.id))
        assertEquals(1L, world.ledger.totalAt(steve, pickaxe)?.raw)
    }

    @Test
    fun `with two of the same tool the change goes to the one whose last mark ends where it starts`() = runTest {
        val world = LedgerHarness()
        val wear = InMemoryWearLog()
        val capture = WearCapture(world.repo, world.log, wear, world::nextTxn, world::nextSeq)
        val steve = player(1)
        val worn = world.ledger.mint(steve, pickaxe, Quantity(1), world.nextTxn())
        val fresh = world.ledger.mint(steve, pickaxe, Quantity(1), world.nextTxn())

        capture.record(steve, pickaxe, 0, 5, 1_000, at = null)
        capture.record(steve, pickaxe, 0, 1, 2_000, at = null)
        capture.record(steve, pickaxe, 5, 6, 3_000, at = null)

        val marks = wear.marksOf(listOf(worn.id, fresh.id))
        assertEquals(listOf(5, 6), marks[worn.id]?.map { it.after })
        assertEquals(listOf(1), marks[fresh.id]?.map { it.after })
    }
}
