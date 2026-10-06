package com.tracel.tests.rollback.plan

import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.placedBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuralPlanTest {
    private val world = LedgerHarness()
    private val steve = player(1)

    @Test
    fun `material alone leaves a placed block alone`() = runTest {
        val chest = block(0, 64, 0)
        val placed = placedBlock(1, 64, 0)
        val root = world.mint(steve, diamond, 64)
        world.move(steve, placed, diamond, 1)
        world.move(steve, chest, diamond, 63)

        val whole = world.planner().plan(listOf(root.id))
        assertEquals(64L, whole.steps.filterIsInstance<RollbackStep.Take>().sumOf { it.quantity.raw })
        assertTrue(whole.steps.any { it is RollbackStep.Take && it.holder is HolderId.PlacedBlock })

        val itemsOnly = world.planner(structural = false).plan(listOf(root.id))
        assertEquals(63L, itemsOnly.steps.filterIsInstance<RollbackStep.Take>().sumOf { it.quantity.raw })
        assertTrue(
            itemsOnly.steps.none { it is RollbackStep.Take && it.holder is HolderId.PlacedBlock },
            "nothing is planned against a block no structural step will break",
        )
    }

    @Test
    fun `a craft chain whose product was built into the world is left alone, and counted`() = runTest {
        val log = ItemKey("minecraft:oak_log")
        val planks = ItemKey("minecraft:oak_planks")
        val table = ItemKey("minecraft:crafting_table")
        val root = world.mint(steve, log, 1)
        world.craft(steve, log, 1, planks, 4)
        world.craft(steve, planks, 4, table, 1)
        world.move(steve, placedBlock(1, 64, 0), table, 1)

        val whole = world.planner()
        assertEquals(2, whole.plan(listOf(root.id)).steps.count { it is RollbackStep.Unmake })
        assertEquals(0, whole.placedAndUnreachable)

        val itemsOnly = world.planner(structural = false)
        assertTrue(itemsOnly.plan(listOf(root.id)).steps.isEmpty(), "material alone can reach none of it")
        assertTrue(itemsOnly.placedAndUnreachable > 0, "and it is counted rather than silently dropped")
    }
}
