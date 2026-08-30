package com.tracel.tests.ownership

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.job.RollbackJobCoordinator
import com.tracel.engine.rollback.job.RollbackOutcome
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

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
            JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn),
            world.jobs,
        )

        val outcome = coordinator.run(RollbackJobId(1), listOf(root.id), target = RollbackTarget.Uniform(chest))

        assertInstanceOf(RollbackOutcome.Applied::class.java, outcome)
        assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw)

        val plan = (outcome as RollbackOutcome.Applied).plan
        assertEquals(plan.steps.size, world.log.all().sumOf { it.flows.size }, "direct delivery logs one flow per step, no escrow round-trip")
        assertEquals(world.log.all().size, world.log.all().map { it.id }.toSet().size, "every logged transaction must have gotten a distinct TxnId")
    }

    @Test
    fun `a rollback of many steps logs one transaction for the batch, not one per step`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val roots = (1..8).map { world.ledger.mint(chest, diamond, Quantity(1), world.nextTxn()).id }
        world.ledger.move(chest, steve, diamond, Quantity(8), world.nextTxn())

        val coordinator = RollbackJobCoordinator(
            world.repo,
            { true },
            world.leases,
            JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn),
            world.jobs,
        )
        val before = world.log.all().size

        val outcome = coordinator.run(RollbackJobId(1), roots, target = RollbackTarget.Uniform(chest))

        val plan = assertInstanceOf(RollbackOutcome.Applied::class.java, outcome).plan
        assertEquals(8, plan.steps.size, "one step per lot taken back")
        assertEquals(8L, world.ledger.totalAt(chest, diamond)?.raw, "everything came home regardless")

        val written = world.log.all().drop(before)
        assertEquals(1, written.size, "direct delivery is one transaction, not a take plus a release")
        assertEquals(plan.steps.size, written.first().flows.size, "and it still carries a flow for every step it did")
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
            JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn),
            world.jobs,
        )
        val outcome = coordinator.run(RollbackJobId(1), listOf(root.id), target = RollbackTarget.Uniform(chest))

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
            JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn),
            world.jobs,
        )
        val outcome = coordinator.run(RollbackJobId(2), listOf(rootB.id), target = RollbackTarget.Uniform(chestB))

        assertInstanceOf(RollbackOutcome.Applied::class.java, outcome)
        assertEquals(5L, world.ledger.totalAt(chestB, diamond)?.raw, "B's non-overlapping territory rolled back fine while A is still active")
    }

    @Test
    fun `a ledger that moves between planning and reserving is still caught`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        var version = 0L //
        var readings = 0
        val witness = suspend {
            if (readings++ == 1) {
                world.ledger.move(steve, chest, diamond, Quantity(10), world.nextTxn())
                version++
            }
            version
        }

        val coordinator = RollbackJobCoordinator(
            world.repo,
            { true },
            world.leases,
            JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn),
            world.jobs,
            ledgerVersion = witness,
        )

        val outcome = coordinator.run(RollbackJobId(1), listOf(root.id), target = RollbackTarget.Uniform(chest))
        assertInstanceOf(RollbackOutcome.Stale::class.java, outcome)
    }

    @Test
    fun `an unmoved ledger applies without replanning`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        var readings = 0
        val coordinator = RollbackJobCoordinator(
            world.repo,
            { true },
            world.leases,
            JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn),
            world.jobs,
            ledgerVersion = { readings++; 7L },
        )

        assertInstanceOf(
            RollbackOutcome.Applied::class.java,
            coordinator.run(RollbackJobId(1), listOf(root.id), target = RollbackTarget.Uniform(chest)),
        )
        assertEquals(2, readings, "the witness is read once after planning and once after reserving")
    }
}
