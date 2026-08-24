package com.tracel.tests.rollback

import com.tracel.engine.journal.CrashPoint
import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.journal.SimulatedCrash
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackJobRecord
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.engine.rollback.involution.InvolutionExecutor
import com.tracel.engine.rollback.involution.InvolutionJobCoordinator
import com.tracel.engine.rollback.involution.InvolutionOutcome
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [InvolutionJobCoordinator] end to end.
 *
 * [InvolutionTest][com.tracel.tests.property.InvolutionTest] already proves the ledger-level
 * involution mechanism is correct by driving [InvolutionExecutor] by hand.
 *
 * This proves the coordinator's acquire -> plan -> apply -> release loop does the same
 * thing a real in-game command would.
 */
class InvolutionJobCoordinatorTest {
    @Test
    fun `undoing an unknown job is reported as not found`() = runTest {
        val world = LedgerHarness()
        val coordinator = InvolutionJobCoordinator(
            world.jobs,
            world.repo,
            world.leases,
            InvolutionExecutor(world.ledger, world.log, world::nextSeq),
            InMemoryJournal(),
            world::nextTxn,
        )

        val outcome = coordinator.undo(RollbackJobId(404))

        assertInstanceOf(InvolutionOutcome.NotFound::class.java, outcome)
    }

    @Test
    fun `undoing a completed take-plus-mint rollback restores the pre-rollback state`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())
        world.ledger.burn(steve, diamond, Quantity(4), SinkKind.LAVA, world.nextTxn())

        val job = RollbackJobId(1)
        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(lease, plan, restoreTo = chest)
        world.jobs.save(RollbackJobRecord(job, plan, chest))

        assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw, "sanity: rollback did apply")

        val coordinator = InvolutionJobCoordinator(
            world.jobs,
            world.repo,
            world.leases,
            InvolutionExecutor(world.ledger, world.log, world::nextSeq),
            InMemoryJournal(),
            world::nextTxn,
        )
        val outcome = coordinator.undo(job)

        assertInstanceOf(InvolutionOutcome.Undone::class.java, outcome)
        assertNull(world.ledger.totalAt(chest, diamond), "the chest gives back everything the rollback put there")
        assertEquals(6L, world.ledger.totalAt(steve, diamond)?.raw, "Steve gets back exactly what was actually recovered")
        assertEquals(6L, world.ledger.census(diamond), "back to the pre-rollback census — the burned 4 stay burned")
    }

    @Test
    fun `undoing an already-undone job is reported distinctly, and the ledger does not move again`() = runTest {
        // Caught live: a repeated /tracel rollback undo on an already-undone job kept calling
        // physical restoration again, which has no memory of its own the way the ledger-level
        // journal does — the give-back side of a Return step kept re-adding material to a chest on
        // every repeated call. AlreadyUndone lets a caller tell "just finished" from "already
        // finished before this call" and skip physical restoration on the latter.
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())
        world.ledger.burn(steve, diamond, Quantity(4), SinkKind.LAVA, world.nextTxn())

        val job = RollbackJobId(1)
        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(lease, plan, restoreTo = chest)
        world.jobs.save(RollbackJobRecord(job, plan, chest))

        val undoJournal = InMemoryJournal()
        val coordinator = InvolutionJobCoordinator(
            world.jobs,
            world.repo,
            world.leases,
            InvolutionExecutor(world.ledger, world.log, world::nextSeq),
            undoJournal,
            world::nextTxn,
        )

        val first = coordinator.undo(job)
        assertInstanceOf(InvolutionOutcome.Undone::class.java, first)
        val censusAfterFirstUndo = world.ledger.census(diamond)
        val txnCountAfterFirstUndo = world.log.all().size

        val second = coordinator.undo(job)

        assertInstanceOf(InvolutionOutcome.AlreadyUndone::class.java, second)
        assertEquals(censusAfterFirstUndo, world.ledger.census(diamond), "a repeated undo must not move the ledger again")
        assertEquals(txnCountAfterFirstUndo, world.log.all().size, "a repeated undo must not log any new transaction")
    }

    @Test
    fun `undoing a job blocked by another job's active lease touches nothing`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        val job = RollbackJobId(1)
        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(lease, plan, restoreTo = chest)
        world.jobs.save(RollbackJobRecord(job, plan, chest))

        // A second, unrelated job grabs the same lots before anyone gets around to undoing job 1.
        world.leases.acquire(RollbackJobId(2), plan.touchedLots)

        val coordinator = InvolutionJobCoordinator(
            world.jobs,
            world.repo,
            world.leases,
            InvolutionExecutor(world.ledger, world.log, world::nextSeq),
            InMemoryJournal(),
            world::nextTxn,
        )
        val outcome = coordinator.undo(job)

        assertInstanceOf(InvolutionOutcome.Blocked::class.java, outcome)
        assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw, "nothing moved — undo never ran")
    }

    @Test
    fun `a crash before any undo step still resumes to a correct final state`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())
        world.ledger.burn(steve, diamond, Quantity(4), SinkKind.LAVA, world.nextTxn())

        val job = RollbackJobId(1)
        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(lease, plan, restoreTo = chest)
        world.jobs.save(RollbackJobRecord(job, plan, chest))

        val stepCount = world.jobs.find(job)!!.plan.steps.size

        for (crashAt in 0 until stepCount) {
            // Undo the same completed rollback fresh each iteration — re-does the forward apply
            // too, since a crashed-and-resumed undo needs the same pre-crash state each time.
            val iterationWorld = LedgerHarness()
            val iterationChest = block(0, 64, 0)
            val iterationSteve = player(1)
            val iterationRoot = iterationWorld.ledger.mint(iterationChest, diamond, Quantity(10), iterationWorld.nextTxn())
            iterationWorld.ledger.move(iterationChest, iterationSteve, diamond, Quantity(10), iterationWorld.nextTxn())
            iterationWorld.ledger.burn(iterationSteve, diamond, Quantity(4), SinkKind.LAVA, iterationWorld.nextTxn())
            val iterationPlan = RollbackPlanner(iterationWorld.repo, { true }).plan(listOf(iterationRoot.id))
            val iterationLease = iterationWorld.acquireLease(job, iterationPlan)
            JournalExecutor(RollbackExecutor(iterationWorld.ledger, iterationWorld.log, iterationWorld::nextSeq), InMemoryJournal(), iterationWorld.leases, iterationWorld::nextTxn)
                .execute(iterationLease, iterationPlan, restoreTo = iterationChest)
            iterationWorld.jobs.save(RollbackJobRecord(job, iterationPlan, iterationChest))

            val undoJournal = InMemoryJournal()
            val coordinator = InvolutionJobCoordinator(
                iterationWorld.jobs,
                iterationWorld.repo,
                iterationWorld.leases,
                InvolutionExecutor(iterationWorld.ledger, iterationWorld.log, iterationWorld::nextSeq),
                undoJournal,
                iterationWorld::nextTxn,
            )

            val crash = runCatching { coordinator.undo(job, CrashPoint.before(crashAt)) }.exceptionOrNull()
            assertTrue(crash is SimulatedCrash, "crash before undo step $crashAt should actually have fired, got $crash")

            // Resume: same journal, same job id, fresh coordinator instance (as a restart would give).
            val resumed = InvolutionJobCoordinator(
                iterationWorld.jobs,
                iterationWorld.repo,
                iterationWorld.leases,
                InvolutionExecutor(iterationWorld.ledger, iterationWorld.log, iterationWorld::nextSeq),
                undoJournal,
                iterationWorld::nextTxn,
            ).undo(job)

            assertInstanceOf(InvolutionOutcome.Undone::class.java, resumed)
            assertNull(iterationWorld.ledger.totalAt(iterationChest, diamond), "crash before undo step $crashAt")
            assertEquals(6L, iterationWorld.ledger.totalAt(iterationSteve, diamond)?.raw, "crash before undo step $crashAt")
            assertEquals(6L, iterationWorld.ledger.census(diamond), "crash before undo step $crashAt: no duplication, no loss")
        }
    }
}
