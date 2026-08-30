package com.tracel.tests.demo

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.itemEntity
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DemoScenarioTest {
    @Test
    fun `chest survives TNT, two pickups, a transfer, and a rollback with zero drift`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val otherChest = block(10, 64, 0)
        val entityA = itemEntity(1)
        val entityB = itemEntity(2)
        val alice = player(1)
        val bob = player(2)

        // A chest with 10 diamonds
        val original = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())

        // TNT blows it up, scattering 6 diamonds to one pile and 4 to another
        world.ledger.move(chest, entityA, diamond, Quantity(6), world.nextTxn())
        world.ledger.move(chest, entityB, diamond, Quantity(4), world.nextTxn())
        assertEquals(0L, world.ledger.totalAt(chest, diamond)?.raw ?: 0L, "chest should be empty right after the explosion")

        // Two assholes pick up the piles and move them to their inventories, leaving the piles empty
        world.ledger.move(entityA, alice, diamond, Quantity(6), world.nextTxn())
        world.ledger.move(entityB, bob, diamond, Quantity(4), world.nextTxn())

        // Alice transfers her share to another chest; Bob keeps his
        world.ledger.move(alice, otherChest, diamond, Quantity(6), world.nextTxn())

        assertEquals(10L, world.ledger.census(diamond), "sanity check: nothing should have vanished before the rollback even starts")

        // Rollback: target — the original 10 diamonds, return them to the original chest
        val planner = RollbackPlanner(world.repo, { true })
        val plan = planner.plan(listOf(original.id))
        assertEquals(0, plan.unmakeCount, "no crafting happened in this scenario")
        assertEquals(2, plan.takeCount, "material ended up in exactly two places: the other chest and Bob")

        val executor = RollbackExecutor(world.ledger, world.log, world::nextSeq)
        val journalExecutor = JournalExecutor(executor, InMemoryJournal(), world.leases, world::nextTxn)
        val lease = world.acquireLease(RollbackJobId(1), plan)
        journalExecutor.execute(lease, plan, target = RollbackTarget.Uniform(chest))

        assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw, "the original chest is whole again")
        assertEquals(0L, world.ledger.totalAt(otherChest, diamond)?.raw ?: 0L, "the transferred share was reclaimed")
        assertEquals(0L, world.ledger.totalAt(bob, diamond)?.raw ?: 0L, "Bob's share was reclaimed")
        assertEquals(0L, world.ledger.totalAt(alice, diamond)?.raw ?: 0L, "Alice had already moved hers on, nothing left to take")
        assertEquals(10L, world.ledger.census(diamond), "no duplication, no loss: exactly the original 10 diamonds exist")
    }
}
