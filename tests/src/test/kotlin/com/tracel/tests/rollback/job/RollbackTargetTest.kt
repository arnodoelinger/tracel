package com.tracel.tests.rollback.job

import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.item.ItemKey
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.entity
import com.tracel.tests.support.Fixtures.itemEntity
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RollbackTargetTest {
    private val world = LedgerHarness()
    private val steve = player(1)

    @Test
    fun `a uniform target sends everything to one place`() = runTest {
        val first = block(0, 64, 0)
        val second = block(0, 64, 8)
        val fromFirst = world.mint(first, diamond, 3)
        val fromSecond = world.mint(second, diamond, 5)

        world.rollback(listOf(fromFirst.id, fromSecond.id), steve)

        assertEquals(8L, world.count(steve, diamond))
    }

    @Test
    fun `two chests looted by one player are each refilled from their own loot`() = runTest {
        val first = block(0, 64, 0)
        val second = block(0, 64, 8)
        val fromFirst = world.mint(first, diamond, 3)
        val fromSecond = world.mint(second, diamond, 5)
        world.move(first, steve, diamond, 3)
        world.move(second, steve, diamond, 5)

        world.rollback(
            listOf(fromFirst.id, fromSecond.id),
            RollbackTarget.PerRoot(mapOf(fromFirst.id to first, fromSecond.id to second)),
        )

        assertEquals(3L, world.count(first, diamond))
        assertEquals(5L, world.count(second, diamond))
        assertEquals(0L, world.count(steve, diamond), "the player kept none of it")
    }

    @Test
    fun `contents crafted into something else come back out of it, and the census never moves`() = runTest {
        val chest = block(0, 64, 0)
        val looted = world.mint(chest, diamond, 9)
        world.move(chest, steve, diamond, 9)
        world.craft(steve, diamond, 9, diamondBlock, 1)

        world.rollback(listOf(looted.id), RollbackTarget.PerRoot(mapOf(looted.id to chest)))

        assertEquals(9L, world.count(chest, diamond))
        assertEquals(0L, world.count(steve, diamond))
        assertEquals(0L, world.count(steve, diamondBlock), "the craft was unmade to get them")
        assertEquals(9L, world.census(diamond))
        assertEquals(0L, world.census(diamondBlock))
    }

    @Test
    fun `material passed on to a third player is taken back from them`() = runTest {
        val chest = block(0, 64, 0)
        val bob = player(2)
        val looted = world.mint(chest, diamond, 4)
        world.move(chest, steve, diamond, 4)
        world.move(steve, bob, diamond, 4)

        world.rollback(listOf(looted.id), RollbackTarget.PerRoot(mapOf(looted.id to chest)))

        assertEquals(4L, world.count(chest, diamond))
        assertEquals(0L, world.count(bob, diamond))
        assertEquals(4L, world.census(diamond))
    }

    @Test
    fun `a window that also covers putting the item in gives it back to the player, not the frame`() = runTest {
        val frame = entity(7)
        val ground = itemEntity(2)
        val sword = ItemKey("minecraft:diamond_sword")
        val lot = world.mint(steve, sword, 1)
        world.move(steve, frame, sword, 1)
        world.move(frame, ground, sword, 1)

        world.rollback(listOf(lot.id), RollbackTarget.PerRoot(mapOf(lot.id to steve)))

        assertEquals(1L, world.count(steve, sword), "back where the window started")
        assertEquals(0L, world.count(frame, sword), "putting it in the frame is part of what was undone")
        assertEquals(0L, world.count(ground, sword))
    }

    @Test
    fun `a window that covers only the break puts the item back in the frame`() = runTest {
        val frame = entity(7)
        val ground = itemEntity(2)
        val sword = ItemKey("minecraft:diamond_sword")
        val lot = world.mint(steve, sword, 1)
        world.move(steve, frame, sword, 1)
        world.move(frame, ground, sword, 1)

        world.rollback(listOf(lot.id), RollbackTarget.PerRoot(mapOf(lot.id to frame)))

        assertEquals(1L, world.count(frame, sword))
        assertEquals(0L, world.count(steve, sword), "the player is not handed a second one")
        assertEquals(0L, world.count(ground, sword))
    }
}
