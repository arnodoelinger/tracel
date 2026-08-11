package com.tracel.tests.ownership

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackJobCoordinator
import com.tracel.engine.rollback.RollbackOutcome
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Plan -> acquire -> verify -> apply, end to end. */
class RollbackJobCoordinatorTest {
    @Test
    fun `an unblocked rollback applies normally`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        val coordinator = RollbackJobCoordinator(
            world.repo,
            { true },
            world.leases,
            JournalExecutor(RollbackExecutor(world.ledger), InMemoryJournal(), world.leases),
        )

        val outcome = coordinator.run(RollbackJobId(1), listOf(root.id), restoreTo = chest, txn = world.nextTxn())

        assertInstanceOf(RollbackOutcome.Applied::class.java, outcome)
        assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw)
    }

    @Test
    fun `a plan whose lots are already leased to another job is blocked, nothing applied`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        // Someone else already holds the only lot this rollback needs
        world.leases.acquire(RollbackJobId(99), setOf(root.id))

        val coordinator = RollbackJobCoordinator(
            world.repo,
            { true },
            world.leases,
            JournalExecutor(RollbackExecutor(world.ledger), InMemoryJournal(), world.leases),
        )
        val outcome = coordinator.run(RollbackJobId(1), listOf(root.id), restoreTo = chest, txn = world.nextTxn())

        assertInstanceOf(RollbackOutcome.Blocked::class.java, outcome)
        assertEquals(mapOf(root.id to RollbackJobId(99)), (outcome as RollbackOutcome.Blocked).conflicts)

        // Nothing moved — the diamonds are exactly where they were before the attempt
        assertEquals(10L, world.ledger.totalAt(steve, diamond)?.raw)
    }

    @Test
    fun `a second, non-overlapping rollback runs concurrently with the first still active`() = runTest {
        val world = LedgerHarness()
        val chestA = block(0, 64, 0)
        val chestB = block(100, 64, 0)
        val steve = player(1)
        val bob = player(2)

        val rootA = world.ledger.mint(chestA, diamond, Quantity(5), world.nextTxn())
        world.ledger.move(chestA, steve, diamond, Quantity(5), world.nextTxn())
        val rootB = world.ledger.mint(chestB, diamond, Quantity(5), world.nextTxn())
        world.ledger.move(chestB, bob, diamond, Quantity(5), world.nextTxn())

        // Job A is mid-flight and holds its territory — deliberately never released here
        val planA = RollbackPlanner(world.repo, { true }).plan(listOf(rootA.id))
        world.acquireLease(RollbackJobId(1), planA)

        val coordinator = RollbackJobCoordinator(
            world.repo,
            { true },
            world.leases,
            JournalExecutor(RollbackExecutor(world.ledger), InMemoryJournal(), world.leases),
        )
        val outcome = coordinator.run(RollbackJobId(2), listOf(rootB.id), restoreTo = chestB, txn = world.nextTxn())

        assertInstanceOf(RollbackOutcome.Applied::class.java, outcome)
        assertEquals(5L, world.ledger.totalAt(chestB, diamond)?.raw, "B's non-overlapping territory rolled back fine while A is still active")
    }
}
