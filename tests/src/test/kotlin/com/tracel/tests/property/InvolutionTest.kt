package com.tracel.tests.property

import com.tracel.annotations.CauseKind
import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.involution.InvolutionExecutor
import com.tracel.engine.rollback.involution.InvolutionPlanner
import com.tracel.engine.rollback.job.InMemoryRollbackJobRepository
import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.itemEntity
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InvolutionTest {
    @Test
    fun `undo does not abort when the dest has already been emptied`() = runTest {
        val world = LedgerHarness()
        val jobs = InMemoryRollbackJobRepository()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val hopper = block(1, 64, 0)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        val job = RollbackJobId(1)
        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        val target = RollbackTarget.Uniform(chest)
        jobs.save(RollbackJobRecord(job, plan, target))
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(world.acquireLease(job, plan), plan, target)

        world.ledger.move(chest, hopper, diamond, Quantity(10), world.nextTxn())

        val steps = InvolutionPlanner(world.repo).plan(jobs.find(job)!!)
        val executor = InvolutionExecutor(world.ledger, world.log, world::nextSeq)
        val lease = world.acquireLease(job, plan)
        for (step in steps) executor.apply(lease, step, world.nextTxn())
        world.leases.release(job)

        assertEquals(10L, world.ledger.totalAt(hopper, diamond)?.raw)
        assertNull(world.ledger.totalAt(chest, diamond))
    }

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
        jobs.save(RollbackJobRecord(job, plan, RollbackTarget.Uniform(chest)))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(lease, plan, target = RollbackTarget.Uniform(chest))

        // Sanity check the rollback itself did what NoDupeTest already proves it does
        assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw)
        assertEquals(10L, world.ledger.census(diamond))

        // JournalExecutor already released the lease once the rollback completed successfully —
        // undoing is a new operation over the same lots, so it re-acquires its own, exactly like
        // a real /tracel rollback undo command would have to.
        val undoLease = world.acquireLease(job, plan)
        val involutionSteps = InvolutionPlanner(world.repo).plan(jobs.find(job)!!)
        val involutionExecutor = InvolutionExecutor(world.ledger, world.log, world::nextSeq)
        for (step in involutionSteps) involutionExecutor.apply(undoLease, step, world.nextTxn())
        world.leases.release(job)

        assertNull(world.ledger.totalAt(chest, diamond), "the chest gives back everything the rollback put there")
        assertEquals(6L, world.ledger.totalAt(steve, diamond)?.raw, "Steve gets back exactly what was actually recovered")
        assertEquals(6L, world.ledger.census(diamond), "back to the pre-rollback census — the burned 4 stay burned")

        val compensated = plan.steps.filterIsInstance<RollbackStep.Mint>().map { it.lotId }.toSet()
        val replan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        assertEquals(emptySet<Any>(), replan.settled, "undoing a rollback un-settles what it compensated")
        assertTrue(
            replan.steps.filterIsInstance<RollbackStep.Mint>().map { it.lotId }.containsAll(compensated),
            "every lot the first rollback compensated is compensable again: $replan",
        )
    }

    @Test
    fun `a vanished take still empties the dest the rollback filled`() = runTest {
        val world = LedgerHarness()
        val jobs = InMemoryRollbackJobRepository()
        val chest = block(0, 64, 0)
        val drop = itemEntity(7)

        val root = world.ledger.mint(chest, diamond, Quantity(13), world.nextTxn())
        world.ledger.move(chest, drop, diamond, Quantity(13), world.nextTxn())

        val job = RollbackJobId(1)
        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        val target = RollbackTarget.Uniform(chest)
        jobs.save(RollbackJobRecord(job, plan, target))
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(world.acquireLease(job, plan), plan, target)

        assertEquals(13L, world.ledger.totalAt(chest, diamond)?.raw)

        val steps = InvolutionPlanner(world.repo).plan(jobs.find(job)!!, vanished = setOf(drop))
        val returned = steps.filterIsInstance<InvolutionStep.Return>().single()
        assertEquals(chest, returned.from)
        assertEquals(drop, returned.to)

        val executor = InvolutionExecutor(world.ledger, world.log, world::nextSeq)
        val lease = world.acquireLease(job, plan)
        for (step in steps) executor.apply(lease, step, world.nextTxn())
        world.leases.release(job)

        assertNull(world.ledger.totalAt(chest, diamond), "the chest gives back what the rollback put there, even if the drop is gone")
        assertEquals(13L, world.ledger.totalAt(drop, diamond)?.raw)
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
        jobs.save(RollbackJobRecord(job, plan, RollbackTarget.Uniform(chest)))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(lease, plan, target = RollbackTarget.Uniform(chest))

        // Sanity check the rollback itself did what CraftUnmakeTest already proves it does
        assertEquals(4L, world.ledger.totalAt(chest, diamond)?.raw)
        assertEquals(5L, world.ledger.totalAt(steve, diamond)?.raw)
        assertNull(world.ledger.totalAt(steve, diamondBlock))

        // JournalExecutor already released the lease once the rollback completed successfully —
        // undoing is a new operation over the same lots, so it re-acquires its own, exactly like
        // a real /tracel rollback undo command would have to.
        val undoLease = world.acquireLease(job, plan)
        val involutionSteps = InvolutionPlanner(world.repo).plan(jobs.find(job)!!)
        val involutionExecutor = InvolutionExecutor(world.ledger, world.log, world::nextSeq)
        for (step in involutionSteps) involutionExecutor.apply(undoLease, step, world.nextTxn())
        world.leases.release(job)

        assertEquals(1L, world.ledger.totalAt(steve, diamondBlock)?.raw, "the block is re-crafted")
        assertNull(world.ledger.totalAt(steve, diamond), "all 9 diamonds go back into the re-crafted block")
        assertNull(world.ledger.totalAt(chest, diamond), "the chest gives back the 4 it received from the rollback")
        assertEquals(0L, world.ledger.census(diamond), "back to the pre-rollback census: no loose diamonds")
        assertEquals(1L, world.ledger.census(diamondBlock), "back to the pre-rollback census: exactly one block")

        val unmakeTxn = world.log.all().single { it.cause == CauseKind.ROLLBACK && it.flows.any { f -> f.kind == FlowKind.TRANSFORM_IN } }
        assertEquals(diamondBlock, unmakeTxn.flows.single { it.kind == FlowKind.TRANSFORM_IN }.itemKey, "the destroyed output is the TRANSFORM_IN side")
        assertTrue(unmakeTxn.flows.filter { it.kind == FlowKind.TRANSFORM_OUT }.all { it.itemKey == diamond }, "every restored ingredient flow is the TRANSFORM_OUT side")
        assertEquals(9L, unmakeTxn.flows.filter { it.kind == FlowKind.TRANSFORM_OUT }.sumOf { it.quantity.raw }, "all 9 diamonds restored across however many lots the craft consumed")

        val remakeTxn = world.log.all().single { it.cause == CauseKind.INVOLUTION && it.flows.any { f -> f.kind == FlowKind.TRANSFORM_OUT } }
        assertTrue(remakeTxn.flows.filter { it.kind == FlowKind.TRANSFORM_IN }.all { it.itemKey == diamond }, "every re-consumed ingredient flow is the TRANSFORM_IN side")
        assertEquals(9L, remakeTxn.flows.filter { it.kind == FlowKind.TRANSFORM_IN }.sumOf { it.quantity.raw }, "all 9 diamonds re-consumed")
        assertEquals(diamondBlock, remakeTxn.flows.single { it.kind == FlowKind.TRANSFORM_OUT }.itemKey, "the re-crafted block is the TRANSFORM_OUT side")
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
        jobs.save(RollbackJobRecord(job, plan, RollbackTarget.Uniform(chest)))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(lease, plan, target = RollbackTarget.Uniform(chest))

        // Job 1's own lease was released automatically on success — but before anyone gets
        // around to undoing it, a second, unrelated job starts working the exact same lots
        // (e.g. tracing the same material further).
        world.leases.acquire(RollbackJobId(2), plan.touchedLots)

        val denied = world.leases.acquire(job, jobs.find(job)!!.plan.touchedLots)
        assertInstanceOf(LeaseAcquisition.Denied::class.java, denied)
    }
}
