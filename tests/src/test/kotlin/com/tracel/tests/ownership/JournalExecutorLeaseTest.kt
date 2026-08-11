package com.tracel.tests.ownership

import com.tracel.engine.journal.CrashPoint
import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.journal.SimulatedCrash
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The half of ownership that has nothing to do with detecting conflicts: a job that finishes
 * cleanly must let go of what it held, and a job interrupted mid-flight must not — its lease
 * has to survive exactly as long as the job might still resume.
 */
class JournalExecutorLeaseTest {
    @Test
    fun `a successfully completed job releases its lease`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val job = RollbackJobId(1)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        val lease = world.acquireLease(job, plan)
        JournalExecutor(RollbackExecutor(world.ledger), InMemoryJournal(), world.leases)
            .execute(lease, plan, restoreTo = chest, txn = world.nextTxn())

        assertInstanceOf(LeaseAcquisition.Granted::class.java, world.leases.acquire(RollbackJobId(2), plan.touchedLots))
    }

    @Test
    fun `a job interrupted mid-flight keeps its lease held`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val job = RollbackJobId(1)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        val lease = world.acquireLease(job, plan)

        val crash = runCatching {
            JournalExecutor(RollbackExecutor(world.ledger), InMemoryJournal(), world.leases)
                .execute(lease, plan, restoreTo = chest, txn = world.nextTxn(), crashPoint = CrashPoint.before(0))
        }.exceptionOrNull()
        assertTrue(crash is SimulatedCrash)

        val denied = world.leases.acquire(RollbackJobId(2), plan.touchedLots)
        assertInstanceOf(LeaseAcquisition.Denied::class.java, denied)
    }
}
