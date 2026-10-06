package com.tracel.tests.rollback.plan

import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.Quantity
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.entity
import com.tracel.tests.support.Fixtures.itemEntity
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompensationTest {
    private val world = LedgerHarness()
    private val chest = block(0, 64, 0)
    private val steve = player(1)

    @Test
    fun `a burned portion is compensated exactly, not over- or under-minted`() = runTest {
        val root = world.mint(chest, diamond, 10)
        world.move(chest, steve, diamond, 10)
        world.burn(steve, diamond, 4)
        assertEquals(6L, world.census(diamond), "burning is a real loss, visible in the census")

        val plan = world.rollback(listOf(root.id), chest).plan

        val mint = plan.steps.filterIsInstance<RollbackStep.Mint>().single()
        assertEquals(4L, mint.quantity.raw)
        assertEquals(SinkKind.HAZARD, mint.reason)
        assertEquals(10L, world.count(chest, diamond))
        assertEquals(10L, world.census(diamond), "6 recovered plus 4 compensated")
    }

    @Test
    fun `rolling back an already compensated lot again plans nothing and mints nothing`() = runTest {
        val root = world.mint(steve, diamond, 4)
        world.burn(steve, diamond, 4)

        world.rollback(listOf(root.id), steve)
        assertEquals(4L, world.count(steve, diamond), "the first rollback compensates the burned material")

        val replan = world.planner().plan(listOf(root.id))
        assertEquals(setOf(root.id), replan.settled)
        assertTrue(replan.steps.isEmpty(), "nothing left to plan: ${replan.steps}")

        world.rollback(listOf(root.id), steve)
        assertEquals(4L, world.census(diamond), "the repeat minted nothing")
    }

    @Test
    fun `a burned craft output is compensated at the ingredients, not unmade`() = runTest {
        val root = world.mint(steve, diamond, 9)
        world.craft(steve, diamond, 9, diamondBlock, 1)
        assertTrue(world.planner().plan(listOf(root.id)).steps.any { it is RollbackStep.Unmake })

        world.burn(steve, diamondBlock, 1, SinkKind.UNATTRIBUTED)

        assertEquals(
            listOf(RollbackStep.Mint(root.id, Quantity(9), SinkKind.UNATTRIBUTED)),
            world.planner().plan(listOf(root.id)).steps,
            "no output left to take apart, so the ingredients are compensated once",
        )
    }

    @Test
    fun `a lot on a ground item that no longer exists is compensated, not taken`() = runTest {
        val ground = itemEntity(7)
        val root = world.mint(ground, diamond, 3)

        val naive = world.planner().plan(listOf(root.id))
        assertEquals(setOf<HolderId>(ground), naive.holders, "as far as the ledger knows it is still down there")
        assertTrue(naive.steps.single() is RollbackStep.Take)

        val informed = world.planner(vanished = setOf(ground)).plan(listOf(root.id))
        val step = informed.steps.single()
        assertTrue(step is RollbackStep.Mint, "told the item is gone, it compensates instead: $step")
        assertEquals(Quantity(3), (step as RollbackStep.Mint).quantity)
        assertTrue(informed.holders.isEmpty(), "and asks nothing of a holder that is not there")
    }

    @Test
    fun `a lot on an entity that no longer exists is compensated, not taken`() = runTest {
        val boat = entity(9)
        val root = world.mint(boat, diamond, 2)

        val informed = world.planner(vanished = setOf(boat)).plan(listOf(root.id))

        assertTrue(informed.steps.single() is RollbackStep.Mint, "compensates instead: ${informed.steps}")
        assertTrue(informed.holders.isEmpty())
    }
}
