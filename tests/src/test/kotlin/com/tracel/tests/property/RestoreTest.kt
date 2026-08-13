package com.tracel.tests.property

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.engine.rollback.WorldQuery
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Restore: no matter how many hops a traced batch's material takes
 * afterward — split three ways, passed between four different holders —
 * rolling back to its root reproduces the same real-world census the root
 * represented at the checkpoint.
 */
class RestoreTest {
    @Test
    fun `census after rollback matches the census at the traced checkpoint`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val p1 = player(1)
        val p2 = player(2)
        val p3 = player(3)

        val root = world.ledger.mint(chest, diamond, Quantity(20), world.nextTxn())
        val checkpointCensus = world.ledger.census(diamond)

        // Later: an arbitrary chain of moves and splits, which we don't need to track manually —
        // the rollback must converge to the same result regardless of how many times the material
        // changed hands.
        world.ledger.move(chest, p1, diamond, Quantity(12), world.nextTxn())
        world.ledger.move(p1, p2, diamond, Quantity(5), world.nextTxn())
        world.ledger.move(chest, p3, diamond, Quantity(8), world.nextTxn())
        world.ledger.move(p2, p3, diamond, Quantity(2), world.nextTxn())
        world.ledger.move(p3, p1, diamond, Quantity(1), world.nextTxn())

        val plan = RollbackPlanner(world.repo, WorldQuery { true }).plan(listOf(root.id))
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(world.acquireLease(RollbackJobId(1), plan), plan, restoreTo = chest)

        assertEquals(checkpointCensus, world.ledger.census(diamond))
        assertEquals(20L, world.ledger.totalAt(chest, diamond)?.raw)
    }
}
