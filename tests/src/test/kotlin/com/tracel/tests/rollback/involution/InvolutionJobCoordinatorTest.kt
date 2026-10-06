package com.tracel.tests.rollback.involution

import com.tracel.engine.rollback.involution.InvolutionOutcome
import com.tracel.engine.rollback.journal.crash.CrashPoint
import com.tracel.engine.rollback.journal.crash.SimulatedCrash
import com.tracel.engine.rollback.journal.memory.InMemoryJournal
import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InvolutionJobCoordinatorTest {
    private val chest = block(0, 64, 0)
    private val steve = player(1)

    private data class Rolled(val world: LedgerHarness, val root: LotId, val job: RollbackJobId)

    private suspend fun rolledBack(): Rolled {
        val world = LedgerHarness()
        val root = world.mint(chest, diamond, 10).id
        world.move(chest, steve, diamond, 10)
        world.burn(steve, diamond, 4)
        return Rolled(world, root, world.rollback(listOf(root), chest).job)
    }

    @Test
    fun `undoing an unknown job is reported as not found`() = runTest {
        val outcome = LedgerHarness().involutionCoordinator().undo(RollbackJobId(404))

        assertInstanceOf(InvolutionOutcome.NotFound::class.java, outcome)
    }

    @Test
    fun `undoing a take-plus-mint rollback restores the pre-rollback state exactly`() = runTest {
        val (world, root, job) = rolledBack()
        assertEquals(10L, world.count(chest, diamond), "the rollback itself applied")
        val compensated = world.jobs.find(job)!!.plan.steps.filterIsInstance<RollbackStep.Mint>().map { it.lotId }

        world.undo(job)

        assertEquals(0L, world.count(chest, diamond), "the chest gives back everything the rollback put there")
        assertEquals(6L, world.count(steve, diamond), "the player gets back exactly what was actually recovered")
        assertEquals(6L, world.census(diamond), "the burned 4 stay burned")

        val replan = world.planner().plan(listOf(root))
        assertTrue(replan.settled.isEmpty(), "undoing a rollback un-settles what it compensated")
        assertTrue(replan.steps.filterIsInstance<RollbackStep.Mint>().map { it.lotId }.containsAll(compensated))
    }

    @Test
    fun `a second undo of the same job is reported and moves nothing`() = runTest {
        val (world, _, job) = rolledBack()
        val coordinator = world.involutionCoordinator()
        assertInstanceOf(InvolutionOutcome.Undone::class.java, coordinator.undo(job))
        val census = world.census(diamond)
        val logged = world.log.all().size

        assertInstanceOf(InvolutionOutcome.AlreadyUndone::class.java, coordinator.undo(job))

        assertEquals(census, world.census(diamond))
        assertEquals(logged, world.log.all().size, "and logs no new transaction")
    }

    @Test
    fun `undo is blocked while another job leases the same lots, and touches nothing`() = runTest {
        val (world, _, job) = rolledBack()
        world.leases.acquire(RollbackJobId(2), world.jobs.find(job)!!.plan.touchedLots)

        assertInstanceOf(InvolutionOutcome.Blocked::class.java, world.involutionCoordinator().undo(job))
        assertEquals(10L, world.count(chest, diamond), "undo never ran")
    }

    @Test
    fun `a crash before any undo step still resumes to a correct final state`() = runTest {
        val probe = rolledBack()
        val stepCount = probe.world.undo(probe.job).steps.size

        for (crashAt in 0 until stepCount) {
            val (world, _, job) = rolledBack()
            val journal = InMemoryJournal()

            val crash = runCatching { world.involutionCoordinator(journal).undo(job, CrashPoint.before(crashAt)) }
            assertTrue(crash.exceptionOrNull() is SimulatedCrash, "crash before undo step $crashAt did not fire")

            val resumed = world.involutionCoordinator(journal).undo(job)

            assertInstanceOf(InvolutionOutcome.Undone::class.java, resumed)
            assertEquals(0L, world.count(chest, diamond), "crash before undo step $crashAt")
            assertEquals(6L, world.count(steve, diamond), "crash before undo step $crashAt")
            assertEquals(6L, world.census(diamond), "crash before undo step $crashAt: no duplication, no loss")
        }
    }
}
