package com.tracel.tests.demo

import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.craft.Product
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.journal.JournalExecutor
import com.tracel.engine.rollback.journal.memory.InMemoryJournal
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.item.Quantity
import com.tracel.model.rollback.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CraftUnmakeTest {

    @Test
    fun `unmaking a craft returns only the traced share, not the whole item`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)

        // Steve firstly mined 5 of his own diamonds — they are older in the queue than the looted ones
        world.ledger.mint(steve, diamond, Quantity(5), world.nextTxn())

        // Stoled diamonds are younger than Steve's own, so they are the ones that should be returned to the chest
        val looted = world.ledger.mint(chest, diamond, Quantity(4), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(4), world.nextTxn())

        // Craft from all 9. FIFO decides which lots will be used, not the test
        world.ledger.craft(
            listOf(Ingredient(steve, diamond, Quantity(9))),
            Product(steve, diamondBlock, Quantity(1)),
            world.nextTxn(),
        )
        assertEquals(1L, world.ledger.totalAt(steve, diamondBlock)?.raw, "the block exists")
        assertEquals(null, world.ledger.totalAt(steve, diamond), "all 9 diamonds went into it")

        // Rollback: only the 4 looted diamonds should be returned to the chest, Steve keeps his own 5
        val planner = RollbackPlanner(world.repo, { true })
        val plan = planner.plan(listOf(looted.id))
        assertEquals(1, plan.unmakeCount, "recovering the loot requires unmaking exactly one craft")
        assertEquals(1, plan.takeCount, "and one take, for the traced share")

        val executor = RollbackExecutor(world.ledger, world.log, world::nextSeq)
        JournalExecutor(executor, InMemoryJournal(), world.leases, world::nextTxn)
            .execute(world.acquireLease(RollbackJobId(1), plan), plan, target = RollbackTarget.Uniform(chest))

        assertEquals(4L, world.ledger.totalAt(chest, diamond)?.raw, "the 4 looted diamonds are back in the chest")
        assertEquals(5L, world.ledger.totalAt(steve, diamond)?.raw, "Steve keeps exactly his own 5 — not 9, not 0")
        assertEquals(null, world.ledger.totalAt(steve, diamondBlock), "the crafted block no longer exists")
        assertEquals(9L, world.ledger.census(diamond), "4 in the chest + 5 with Steve: nothing minted, nothing lost")

        // Not only the quantity — the chest contains exactly the looted lot, not some other lot with the same total
        val backInChest = world.repo.accountQueue(chest, diamond).map { it.lot.id }
        assertTrue(looted.id in backInChest, "the specific looted lot is what came back, not merely 4 diamonds")
    }
}
