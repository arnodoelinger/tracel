package com.tracel.tests.property

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.involution.InvolutionExecutor
import com.tracel.engine.rollback.involution.InvolutionPlanner
import com.tracel.engine.rollback.job.InMemoryRollbackJobRepository
import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RepeatedUndoCycleTest {
    private val chest = block(0, 64, 0)
    private val steve = player(1)

    @Test
    fun `a dozen rollback-undo cycles leave the ledger exactly where they found it`() = runTest {
        val world = LedgerHarness()
        val jobs = InMemoryRollbackJobRepository()

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        assertEquals(10L, world.ledger.totalAt(steve, diamond)?.raw)
        assertNull(world.ledger.totalAt(chest, diamond))
        assertEquals(10L, world.ledger.census(diamond))

        repeat(12) { cycle ->
            val job = RollbackJobId(cycle + 1L)

            val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
            val target = RollbackTarget.Uniform(chest)
            jobs.save(RollbackJobRecord(job, plan, target))
            JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
                .execute(world.acquireLease(job, plan), plan, target)

            assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw, "cycle $cycle: the rollback put it back in the chest")
            assertNull(world.ledger.totalAt(steve, diamond), "cycle $cycle: and took it off the player")
            assertEquals(10L, world.ledger.census(diamond), "cycle $cycle: a rollback creates nothing")

            val undoLease = world.acquireLease(job, plan)
            val executor = InvolutionExecutor(world.ledger, world.log, world::nextSeq)
            for (step in InvolutionPlanner(world.repo).plan(jobs.find(job)!!)) {
                executor.apply(undoLease, step, world.nextTxn())
            }
            world.leases.release(job)

            assertEquals(10L, world.ledger.totalAt(steve, diamond)?.raw, "cycle $cycle: undo gave it back to the player")
            assertNull(world.ledger.totalAt(chest, diamond), "cycle $cycle: and emptied the chest again")
            assertEquals(10L, world.ledger.census(diamond), "cycle $cycle: an undo creates nothing either")
        }
    }
}
