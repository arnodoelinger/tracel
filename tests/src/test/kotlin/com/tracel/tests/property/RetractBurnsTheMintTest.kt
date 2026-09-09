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
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.lot.LotEdge
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RetractBurnsTheMintTest {
    private val chest = block(0, 64, 0)
    private val steve = player(1)

    @Test
    fun `undoing a compensation destroys the minted lot and leaves older stock alone`() = runTest {
        val world = LedgerHarness()
        val jobs = InMemoryRollbackJobRepository()

        val older = world.ledger.mint(chest, diamond, Quantity(20), world.nextTxn())

        val root = world.ledger.mint(steve, diamond, Quantity(10), world.nextTxn())
        world.ledger.burn(steve, diamond, Quantity(4), SinkKind.LAVA, world.nextTxn())

        val job = RollbackJobId(1)
        val target = RollbackTarget.Uniform(chest)
        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        jobs.save(RollbackJobRecord(job, plan, target))
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(world.acquireLease(job, plan), plan, target)

        val burned = plan.steps.filterIsInstance<com.tracel.engine.rollback.plan.RollbackStep.Mint>().single().lotId
        val minted = world.repo.edgesFrom(burned).filterIsInstance<LotEdge.Compensate>().single().child
        assertNotNull(world.repo.placementOf(chest, minted), "the compensation is in the chest")

        val undoLease = world.acquireLease(job, plan)
        val executor = InvolutionExecutor(world.ledger, world.log, world::nextSeq)
        for (step in InvolutionPlanner(world.repo).plan(jobs.find(job)!!)) executor.apply(undoLease, step, world.nextTxn())
        world.leases.release(job)

        assertNull(world.repo.placementOf(chest, minted), "the compensation is what the undo burned")
        assertEquals(
            20L,
            world.repo.placementOf(chest, older.id)?.remaining?.raw,
            "and the chest's own diamonds were never touched",
        )
        assertEquals(26L, world.ledger.census(diamond), "back where the cycle found it: the chest's 20 plus 10 minus the 4 in the lava")

        assertEquals(plan, RollbackPlanner(world.repo, { true }).plan(listOf(root.id)))
    }
}
