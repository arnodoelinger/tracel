package com.tracel.tests.lease

import com.tracel.engine.rollback.journal.crash.CrashPoint
import com.tracel.engine.rollback.journal.crash.SimulatedCrash
import com.tracel.engine.rollback.lease.acquisition.LeaseAcquisition
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.rollback.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JournalExecutorLeaseTest {
    private val world = LedgerHarness()
    private val chest = block(0, 64, 0)
    private val steve = player(1)
    private val job = RollbackJobId(1)
    private val rival = RollbackJobId(2)

    private suspend fun planned() = world.mint(chest, diamond, 10).let { root ->
        world.move(chest, steve, diamond, 10)
        world.planner().plan(listOf(root.id))
    }

    @Test
    fun `a completed job releases its lease`() = runTest {
        val plan = planned()

        world.journalExecutor().execute(world.acquireLease(job, plan), plan, RollbackTarget.Uniform(chest))

        assertInstanceOf(LeaseAcquisition.Granted::class.java, world.leases.acquire(rival, plan.touchedLots))
    }

    @Test
    fun `a job interrupted mid-flight keeps its lease`() = runTest {
        val plan = planned()
        val lease = world.acquireLease(job, plan)

        val crash = runCatching {
            world.journalExecutor().execute(lease, plan, RollbackTarget.Uniform(chest), CrashPoint.before(0))
        }.exceptionOrNull()

        assertTrue(crash is SimulatedCrash)
        assertInstanceOf(LeaseAcquisition.Denied::class.java, world.leases.acquire(rival, plan.touchedLots))
    }

    @Test
    fun `a job that fails gives its lease back`() = runTest {
        val plan = planned()
        val lease = world.acquireLease(job, plan)
        world.burn(steve, diamond, 10)

        val failure = runCatching {
            world.journalExecutor().execute(lease, plan, RollbackTarget.Uniform(chest))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "the step should have failed, got $failure")
        assertInstanceOf(
            LeaseAcquisition.Granted::class.java,
            world.leases.acquire(rival, plan.touchedLots),
            "a failed job is over, its lots must not stay locked to it",
        )
    }
}
