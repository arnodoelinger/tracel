package com.tracel.tests.rollback.involution

import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.LotId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UndoCycleTest {
    private val world = LedgerHarness()
    private val chest = block(0, 64, 0)
    private val steve = player(1)

    private suspend fun cycles(
        times: Int,
        root: LotId,
        home: HolderId,
        afterRollback: suspend (String) -> Unit,
        afterUndo: suspend (String) -> Unit,
    ) {
        var first: RollbackPlan? = null
        repeat(times) { n ->
            val label = "cycle ${n + 1}"
            val applied = world.rollback(listOf(root), home)
            if (first == null) first = applied.plan else assertEquals(first, applied.plan, "$label: the same plan")
            afterRollback(label)
            world.undo(applied.job)
            afterUndo(label)
        }
    }

    @Test
    fun `moved material goes home and back without drift`() = runTest {
        val root = world.mint(chest, diamond, 10)
        world.move(chest, steve, diamond, 10)

        cycles(
            times = 12,
            root = root.id,
            home = chest,
            afterRollback = {
                assertEquals(10L, world.count(chest, diamond), "$it: the rollback put it back")
                assertEquals(0L, world.count(steve, diamond), "$it: and took it off the player")
                assertEquals(10L, world.census(diamond), "$it: a rollback creates nothing")
            },
            afterUndo = {
                assertEquals(10L, world.count(steve, diamond), "$it: undo gave it back to the player")
                assertEquals(0L, world.count(chest, diamond), "$it: and emptied the chest")
                assertEquals(10L, world.census(diamond), "$it: an undo creates nothing either")
            },
        )
    }

    @Test
    fun `an unattributed mint goes to the recovery point and back without drift`() = runTest {
        val berries = ItemKey("minecraft:sweet_berries")
        val root = world.mint(steve, berries, 1)

        cycles(
            times = 12,
            root = root.id,
            home = chest,
            afterRollback = {
                assertEquals(1L, world.count(chest, berries), "$it: at the recovery point")
                assertEquals(1L, world.census(berries), "$it: still one berry in the world")
            },
            afterUndo = {
                assertEquals(1L, world.count(steve, berries), "$it: back with the player")
                assertEquals(1L, world.census(berries), "$it: still one berry in the world")
            },
        )
    }

    @Test
    fun `a craft is unmade and re-made without drift`() = runTest {
        val root = world.mint(chest, diamond, 9)
        world.move(chest, steve, diamond, 9)
        world.craft(steve, diamond, 9, diamondBlock, 1)

        cycles(
            times = 4,
            root = root.id,
            home = chest,
            afterRollback = {
                assertEquals(9L, world.count(chest, diamond), "$it: the ingredients came out of the block")
                assertEquals(0L, world.count(steve, diamondBlock), "$it: and the block is gone")
            },
            afterUndo = {
                assertEquals(1L, world.count(steve, diamondBlock), "$it: the block is back")
                assertEquals(0L, world.count(chest, diamond), "$it: and the chest gave the ingredients back")
                assertEquals(1L, world.census(diamondBlock), "$it: nothing created")
                assertEquals(0L, world.census(diamond), "$it: nothing lost")
            },
        )
        assertTrue(world.planner().plan(listOf(root.id)).steps.any { it is RollbackStep.Unmake })
    }
}
