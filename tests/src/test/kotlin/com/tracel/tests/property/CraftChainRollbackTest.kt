package com.tracel.tests.property

import com.tracel.engine.rollback.journal.memory.InMemoryJournal
import com.tracel.engine.rollback.journal.JournalExecutor
import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.craft.Product
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.job.record.memory.InMemoryRollbackJobRepository
import com.tracel.engine.rollback.job.record.RollbackJobRecord
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.placedBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CraftChainRollbackTest {
    private val steve = player(1)
    private val chest = block(0, 64, 0)
    private val log = ItemKey("minecraft:oak_log")
    private val planks = ItemKey("minecraft:oak_planks")
    private val table = ItemKey("minecraft:crafting_table")
    private val button = ItemKey("minecraft:oak_button")

    @Test
    fun `every craft in a chain is unmade, not only the one whose product is untouched`() = runTest {
        val world = LedgerHarness()
        val jobs = InMemoryRollbackJobRepository()

        val logs = world.ledger.mint(steve, log, Quantity(16), world.nextTxn())
        world.ledger.craft(
            listOf(Ingredient(steve, log, Quantity(16))),
            Product(steve, planks, Quantity(64)),
            world.nextTxn(),
        )
        world.ledger.craft(
            listOf(Ingredient(steve, planks, Quantity(4))),
            Product(steve, table, Quantity(1)),
            world.nextTxn(),
        )
        world.ledger.craft(
            listOf(Ingredient(steve, planks, Quantity(1))),
            Product(steve, button, Quantity(1)),
            world.nextTxn(),
        )

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(logs.id))
        assertEquals(
            3,
            plan.steps.count { it is RollbackStep.Unmake },
            "three crafts in the window, three of them to unmake",
        )

        val job = RollbackJobId(1)
        val target = RollbackTarget.Uniform(chest)
        jobs.save(RollbackJobRecord(job, plan, target))
        JournalExecutor(
            RollbackExecutor(world.ledger, world.log, world::nextSeq),
            InMemoryJournal(),
            world.leases,
            world::nextTxn
        )
            .execute(world.acquireLease(job, plan), plan, target)

        assertEquals(16L, world.ledger.totalAt(chest, log)?.raw, "all sixteen logs come back")
        assertNull(world.ledger.totalAt(steve, planks), "and nothing is left of what they became")
        assertNull(world.ledger.totalAt(steve, table))
        assertNull(world.ledger.totalAt(steve, button))
        assertEquals(16L, world.ledger.census(log), "no logs conjured")
        assertEquals(0L, world.ledger.census(planks), "no planks left over")
    }

    @Test
    fun `a chain whose product was built into the world is left alone, and says so`() = runTest {
        val world = LedgerHarness()
        val bench = placedBlock(1, 64, 0)

        val logs = world.ledger.mint(steve, log, Quantity(1), world.nextTxn())
        world.ledger.craft(
            listOf(Ingredient(steve, log, Quantity(1))),
            Product(steve, planks, Quantity(4)),
            world.nextTxn()
        )
        world.ledger.craft(
            listOf(Ingredient(steve, planks, Quantity(4))),
            Product(steve, table, Quantity(1)),
            world.nextTxn()
        )
        world.ledger.move(steve, bench, table, Quantity(1), world.nextTxn())

        val withWorld = RollbackPlanner(world.repo, { true })
        assertEquals(
            2,
            withWorld.plan(listOf(logs.id)).steps.count { it is RollbackStep.Unmake },
            "a rollback that breaks the table can unmake both crafts",
        )
        assertEquals(0, withWorld.placedAndUnreachable, "and nothing was out of its reach")

        val itemsOnly = RollbackPlanner(world.repo, { true }, structural = false)
        assertTrue(itemsOnly.plan(listOf(logs.id)).steps.isEmpty(), "material alone can reach none of it")
        assertTrue(itemsOnly.placedAndUnreachable > 0, "and it is counted rather than silently dropped")
    }

    @Test
    fun `a craft with a share of its product destroyed is left alone rather than half-unmade`() = runTest {
        val world = LedgerHarness()

        val logs = world.ledger.mint(steve, log, Quantity(16), world.nextTxn())
        world.ledger.craft(
            listOf(Ingredient(steve, log, Quantity(16))),
            Product(steve, planks, Quantity(64)),
            world.nextTxn(),
        )
        world.ledger.burn(steve, planks, Quantity(32), SinkKind.HAZARD, world.nextTxn())

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(logs.id))
        assertTrue(
            plan.steps.none { it is RollbackStep.Unmake },
            "a craft cannot be half-unmade, so it is not unmade at all",
        )
        assertEquals(64L, plan.steps.sumOf { step ->
            when (step) {
                is RollbackStep.Take -> step.quantity.raw
                is RollbackStep.Mint -> step.quantity.raw
                else -> 0L
            }
        }, "the planks are reclaimed and the burned half compensated; sixty-four either way")
    }
}
