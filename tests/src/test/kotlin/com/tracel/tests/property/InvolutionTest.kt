package com.tracel.tests.property

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.rollback.InMemoryRollbackJobRepository
import com.tracel.engine.rollback.InvolutionExecutor
import com.tracel.engine.rollback.InvolutionPlanner
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackJobRecord
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Involution: undoing a rollback returns the ledger to its state right before that rollback
 * ran — not to some earlier, more innocent state. Material a rollback genuinely could not
 * recover (burned in lava, for instance) stays lost; only the compensation for it is retracted.
 */
class InvolutionTest {
    @Test
    fun `undoing a take-plus-mint rollback restores the pre-rollback state exactly`() = runTest {
        val world = LedgerHarness()
        val jobs = InMemoryRollbackJobRepository()
        val chest = block(0, 64, 0)
        val steve = player(1)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())
        world.ledger.burn(steve, diamond, Quantity(4), SinkKind.LAVA, world.nextTxn())

        // The state involution has to reproduce: 6 with Steve, nothing in the chest, census 6
        assertEquals(6L, world.ledger.totalAt(steve, diamond)?.raw)
        assertNull(world.ledger.totalAt(chest, diamond))
        assertEquals(6L, world.ledger.census(diamond))

        val job = RollbackJobId(1)
        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        jobs.save(RollbackJobRecord(job, plan, chest))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger), InMemoryJournal(), world.leases)
            .execute(lease, plan, restoreTo = chest, txn = world.nextTxn())

        // Sanity check the rollback itself did what NoDupeTest already proves it does
        assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw)
        assertEquals(10L, world.ledger.census(diamond))

        // JournalExecutor already released the lease once the rollback completed successfully —
        // undoing is a new operation over the same lots, so it re-acquires its own, exactly like
        // a real /tracel rollback undo command would have to.
        val undoLease = world.acquireLease(job, plan)
        val involutionSteps = InvolutionPlanner(world.repo).plan(jobs.find(job)!!)
        val involutionExecutor = InvolutionExecutor(world.ledger)
        for (step in involutionSteps) involutionExecutor.apply(undoLease, step, world.nextTxn())
        world.leases.release(job)

        assertNull(world.ledger.totalAt(chest, diamond), "the chest gives back everything the rollback put there")
        assertEquals(6L, world.ledger.totalAt(steve, diamond)?.raw, "Steve gets back exactly what was actually recovered")
        assertEquals(6L, world.ledger.census(diamond), "back to the pre-rollback census — the burned 4 stay burned")
    }

    @Test
    fun `undoing an unmake rollback re-crafts the destroyed item`() = runTest {
        val world = LedgerHarness()
        val jobs = InMemoryRollbackJobRepository()
        val chest = block(0, 64, 0)
        val steve = player(1)

        world.ledger.mint(steve, diamond, Quantity(5), world.nextTxn())
        val looted = world.ledger.mint(chest, diamond, Quantity(4), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(4), world.nextTxn())
        world.ledger.craft(
            listOf(Ingredient(steve, diamond, Quantity(9))),
            Product(steve, diamondBlock, Quantity(1)),
            world.nextTxn(),
        )

        // Pre-rollback state: the block exists, Steve holds no loose diamonds, chest is empty
        assertEquals(1L, world.ledger.totalAt(steve, diamondBlock)?.raw)
        assertNull(world.ledger.totalAt(steve, diamond))
        assertNull(world.ledger.totalAt(chest, diamond))

        val job = RollbackJobId(1)
        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(looted.id))
        jobs.save(RollbackJobRecord(job, plan, chest))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger), InMemoryJournal(), world.leases)
            .execute(lease, plan, restoreTo = chest, txn = world.nextTxn())

        // Sanity check the rollback itself did what CraftUnmakeTest already proves it does
        assertEquals(4L, world.ledger.totalAt(chest, diamond)?.raw)
        assertEquals(5L, world.ledger.totalAt(steve, diamond)?.raw)
        assertNull(world.ledger.totalAt(steve, diamondBlock))

        // JournalExecutor already released the lease once the rollback completed successfully —
        // undoing is a new operation over the same lots, so it re-acquires its own, exactly like
        // a real /tracel rollback undo command would have to.
        val undoLease = world.acquireLease(job, plan)
        val involutionSteps = InvolutionPlanner(world.repo).plan(jobs.find(job)!!)
        val involutionExecutor = InvolutionExecutor(world.ledger)
        for (step in involutionSteps) involutionExecutor.apply(undoLease, step, world.nextTxn())
        world.leases.release(job)

        assertEquals(1L, world.ledger.totalAt(steve, diamondBlock)?.raw, "the block is re-crafted")
        assertNull(world.ledger.totalAt(steve, diamond), "all 9 diamonds go back into the re-crafted block")
        assertNull(world.ledger.totalAt(chest, diamond), "the chest gives back the 4 it received from the rollback")
        assertEquals(0L, world.ledger.census(diamond), "back to the pre-rollback census: no loose diamonds")
        assertEquals(1L, world.ledger.census(diamondBlock), "back to the pre-rollback census: exactly one block")
    }

    @Test
    fun `undoing a job is denied while a different job currently holds an overlapping lease`() = runTest {
        val world = LedgerHarness()
        val jobs = InMemoryRollbackJobRepository()
        val chest = block(0, 64, 0)
        val steve = player(1)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        val job = RollbackJobId(1)
        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        jobs.save(RollbackJobRecord(job, plan, chest))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger), InMemoryJournal(), world.leases)
            .execute(lease, plan, restoreTo = chest, txn = world.nextTxn())

        // Job 1's own lease was released automatically on success — but before anyone gets
        // around to undoing it, a second, unrelated job starts working the exact same lots
        // (e.g. tracing the same material further).
        world.leases.acquire(RollbackJobId(2), plan.touchedLots)

        val denied = world.leases.acquire(job, jobs.find(job)!!.plan.touchedLots)
        assertInstanceOf(LeaseAcquisition.Denied::class.java, denied)
    }
}
