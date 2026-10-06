package com.tracel.tests.property

import com.tracel.engine.rollback.journal.memory.InMemoryJournal
import com.tracel.engine.rollback.journal.JournalExecutor
import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.craft.Product
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PerRootRestoreTest {
    private val steve = player(1)

    private suspend fun LedgerHarness.rollback(job: Long, roots: List<LotId>, target: RollbackTarget) {
        val plan = RollbackPlanner(repo, { true }).plan(roots)
        JournalExecutor(RollbackExecutor(ledger, log, ::nextSeq), InMemoryJournal(), leases, ::nextTxn)
            .execute(acquireLease(RollbackJobId(job), plan), plan, target)
    }

    @Test
    fun `two chests looted by one player are refilled from their own chests, not from one`() = runTest {
        val world = LedgerHarness()
        val first = block(0, 64, 0)
        val second = block(0, 64, 8)

        val fromFirst = world.ledger.mint(first, diamond, Quantity(3), world.nextTxn())
        val fromSecond = world.ledger.mint(second, diamond, Quantity(5), world.nextTxn())
        world.ledger.move(first, steve, diamond, Quantity(3), world.nextTxn())
        world.ledger.move(second, steve, diamond, Quantity(5), world.nextTxn())

        world.rollback(
            job = 1,
            roots = listOf(fromFirst.id, fromSecond.id),
            target = RollbackTarget.PerRoot(mapOf(fromFirst.id to first, fromSecond.id to second)),
        )

        assertEquals(3L, world.ledger.totalAt(first, diamond)?.raw, "the three came out of the first chest")
        assertEquals(5L, world.ledger.totalAt(second, diamond)?.raw, "the five came out of the second")
        assertNull(world.ledger.totalAt(steve, diamond), "and the player kept none of it")
    }

    @Test
    fun `contents crafted into something else come back out of it, and the census never moves`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)

        val looted = world.ledger.mint(chest, diamond, Quantity(9), world.nextTxn())
        val before = world.ledger.census(diamond)

        world.ledger.move(chest, steve, diamond, Quantity(9), world.nextTxn())
        world.ledger.craft(
            listOf(Ingredient(steve, diamond, Quantity(9))),
            Product(steve, diamondBlock, Quantity(1)),
            world.nextTxn(),
        )
        assertEquals(1L, world.ledger.totalAt(steve, diamondBlock)?.raw, "the block exists before the rollback")

        world.rollback(job = 1, roots = listOf(looted.id), target = RollbackTarget.PerRoot(mapOf(looted.id to chest)))

        assertEquals(9L, world.ledger.totalAt(chest, diamond)?.raw, "back in the chest it came out of")
        assertNull(world.ledger.totalAt(steve, diamond), "and not also in the griefer's pocket")
        assertNull(world.ledger.totalAt(steve, diamondBlock), "the craft was unmade to get them")
        assertEquals(before, world.ledger.census(diamond), "no diamond was created or destroyed by any of this")
    }

    @Test
    fun `material passed on to a third player is taken back from them`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val bob = player(2)

        val looted = world.ledger.mint(chest, diamond, Quantity(4), world.nextTxn())
        val before = world.ledger.census(diamond)
        world.ledger.move(chest, steve, diamond, Quantity(4), world.nextTxn())
        world.ledger.move(steve, bob, diamond, Quantity(4), world.nextTxn())

        world.rollback(job = 1, roots = listOf(looted.id), target = RollbackTarget.PerRoot(mapOf(looted.id to chest)))

        assertEquals(4L, world.ledger.totalAt(chest, diamond)?.raw)
        assertNull(world.ledger.totalAt(bob, diamond), "passing it on does not put it out of reach")
        assertEquals(before, world.ledger.census(diamond))
    }

    @Test
    fun `a uniform target still sends everything to one place`() = runTest {
        val world = LedgerHarness()
        val first = block(0, 64, 0)
        val second = block(0, 64, 8)
        val fromFirst = world.ledger.mint(first, diamond, Quantity(3), world.nextTxn())
        val fromSecond = world.ledger.mint(second, diamond, Quantity(5), world.nextTxn())

        world.rollback(
            job = 1,
            roots = listOf(fromFirst.id, fromSecond.id),
            target = RollbackTarget.Uniform(steve as HolderId),
        )

        assertEquals(8L, world.ledger.totalAt(steve, diamond)?.raw, "an admin asking by hand gets it all in one place")
    }
}
