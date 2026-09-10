package com.tracel.tests.property

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.involution.InvolutionExecutor
import com.tracel.engine.rollback.involution.InvolutionPlanner
import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.engine.rollback.job.InMemoryRollbackJobRepository
import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CraftUndoCycleTest {
    private val chest = block(0, 64, 0)
    private val steve = player(1)

    @Test
    fun `rolling a craft back and undoing it, repeatedly, plans the same work every time`() = runTest {
        val world = LedgerHarness()
        val jobs = InMemoryRollbackJobRepository()

        val root = world.ledger.mint(chest, diamond, Quantity(9), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(9), world.nextTxn())
        world.ledger.craft(
            listOf(Ingredient(steve, diamond, Quantity(9))),
            Product(steve, diamondBlock, Quantity(1)),
            world.nextTxn(),
        )

        var first: RollbackPlan? = null

        repeat(4) { cycle ->
            val job = RollbackJobId(cycle + 1L)
            val target = RollbackTarget.Uniform(chest)

            val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
            assertTrue(plan.steps.any { it is RollbackStep.Unmake }, "cycle $cycle: the craft has to be unmade")
            if (first == null) first = plan else assertEquals(first, plan, "cycle $cycle: the same window, so the same plan")

            jobs.save(RollbackJobRecord(job, plan, target))
            JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
                .execute(world.acquireLease(job, plan), plan, target)

            assertEquals(9L, world.ledger.totalAt(chest, diamond)?.raw, "cycle $cycle: the ingredients came out of the block")
            assertNull(world.ledger.totalAt(steve, diamondBlock), "cycle $cycle: and the block is gone")

            val undoLease = world.acquireLease(job, plan)
            val steps = InvolutionPlanner(world.repo).plan(jobs.find(job)!!)
            assertTrue(steps.any { it is InvolutionStep.Remake }, "cycle $cycle: the undo has to re-craft")
            val executor = InvolutionExecutor(world.ledger, world.log, world::nextSeq)
            for (step in steps) executor.apply(undoLease, step, world.nextTxn())
            world.leases.release(job)

            assertEquals(1L, world.ledger.totalAt(steve, diamondBlock)?.raw, "cycle $cycle: the block is back")
            assertNull(world.ledger.totalAt(chest, diamond), "cycle $cycle: and the chest gave the ingredients back")
            assertEquals(1L, world.ledger.census(diamondBlock), "cycle $cycle: a cycle creates nothing")
            assertEquals(0L, world.ledger.census(diamond), "cycle $cycle: and loses nothing")
        }
    }
}
