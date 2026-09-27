package com.tracel.tests.property

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class NoDupeTest {
    @Test
    fun `a burned portion is compensated exactly, not over- or under-minted`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())
        assertEquals(10L, world.ledger.census(diamond))

        // 4 of 10 burn in lava — the real material is now 4 less, and the census must honestly show this,
        // not hide it in the accounting.
        world.ledger.burn(steve, diamond, Quantity(4), SinkKind.LAVA, world.nextTxn())
        assertEquals(6L, world.ledger.census(diamond), "burning is a real loss, visible in the census")

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        val mintStep = plan.steps.filterIsInstance<RollbackStep.Mint>().single()
        assertEquals(4L, mintStep.quantity.raw, "compensation must match exactly what was actually lost")
        assertEquals(SinkKind.LAVA, mintStep.reason)

        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(world.acquireLease(RollbackJobId(1), plan), plan, target = RollbackTarget.Uniform(chest))

        // 6 remaining in the chest, 4 newly minted to compensate for the burned ones — census is back to 10
        assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw)
        assertEquals(10L, world.ledger.census(diamond), "I4: mints and burns are always accounted for in the census, never silently lost or duplicated")
    }

    @Test
    fun `rolling back an already-compensated lot again plans nothing, and re-mints nothing`() = runTest {
        // Found live: /tracel rollback apply on the same lot, repeated, minted a fresh
        // replacement every single time — compensate() never moves the original lot's own
        // placement (it stays at the Sink it was burned to), so nothing stopped the planner
        // from seeing "still burned" forever and compensating it again, unboundedly.
        val world = LedgerHarness()
        val steve = player(1)

        val root = world.ledger.mint(steve, diamond, Quantity(4), world.nextTxn())
        world.ledger.burn(steve, diamond, Quantity(4), SinkKind.LAVA, world.nextTxn())

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(world.acquireLease(RollbackJobId(1), plan), plan, target = RollbackTarget.Uniform(steve))
        assertEquals(4L, world.ledger.totalAt(steve, diamond)?.raw, "the first rollback correctly compensates the burned material")

        val replan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        assertEquals(setOf(root.id), replan.settled, "the replan recognizes the lot as already compensated")
        assertTrue(replan.steps.isEmpty(), "and plans nothing for it: ${replan.steps}")

        JournalExecutor(RollbackExecutor(world.ledger, world.log, world::nextSeq), InMemoryJournal(), world.leases, world::nextTxn)
            .execute(world.acquireLease(RollbackJobId(2), replan), replan, target = RollbackTarget.Uniform(steve))
        assertEquals(4L, world.ledger.census(diamond), "still exactly one compensation — the replan minted nothing")
    }

    @Test
    fun `a lot on a ground item that no longer exists is compensated, not taken`() = runTest {
        val world = LedgerHarness()
        val ground = HolderId.ItemEntity(UUID(7, 7))

        val root = world.ledger.mint(ground, diamond, Quantity(3), world.nextTxn())

        val naive = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        assertEquals(setOf(ground), naive.holders, "as far as the ledger knows, it is still down there")
        assertTrue(naive.steps.single() is RollbackStep.Take, "and it would try to take it: ${naive.steps}")

        val informed = RollbackPlanner(world.repo, { true }, vanished = setOf(ground)).plan(listOf(root.id))
        val step = informed.steps.single()
        assertTrue(step is RollbackStep.Mint, "told the item is gone, it compensates instead: $step")
        assertEquals(Quantity(3), (step as RollbackStep.Mint).quantity)
        assertEquals(emptySet<Any>(), informed.holders, "and asks nothing of a holder that is not there")
    }

    @Test
    fun `a lot on an entity that no longer exists is compensated, not taken`() = runTest {
        val world = LedgerHarness()
        val boat = HolderId.Entity(UUID(9, 9))

        val root = world.ledger.mint(boat, diamond, Quantity(2), world.nextTxn())

        val informed = RollbackPlanner(world.repo, { true }, vanished = setOf(boat)).plan(listOf(root.id))
        val step = informed.steps.single()
        assertTrue(step is RollbackStep.Mint, "told the boat is gone, it compensates instead: $step")
        assertEquals(emptySet<Any>(), informed.holders, "and asks nothing of a boat that is not there")
    }
}
