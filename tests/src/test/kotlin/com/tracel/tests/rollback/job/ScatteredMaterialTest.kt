package com.tracel.tests.rollback.job

import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.itemEntity
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScatteredMaterialTest {
    private val world = LedgerHarness()
    private val chest = block(0, 64, 0)

    @Test
    fun `a chest blown apart, picked up and passed on comes back whole`() = runTest {
        val otherChest = block(10, 64, 0)
        val alice = player(1)
        val bob = player(2)
        val pileA = itemEntity(1)
        val pileB = itemEntity(2)
        val original = world.mint(chest, diamond, 10)

        world.move(chest, pileA, diamond, 6)
        world.move(chest, pileB, diamond, 4)
        world.move(pileA, alice, diamond, 6)
        world.move(pileB, bob, diamond, 4)
        world.move(alice, otherChest, diamond, 6)

        val plan = world.rollback(listOf(original.id), chest).plan

        assertEquals(0, plan.unmakeCount)
        assertEquals(2, plan.takeCount, "the material ended up in two places: the other chest and Bob")
        assertEquals(10L, world.count(chest, diamond))
        assertEquals(0L, world.count(otherChest, diamond))
        assertEquals(0L, world.count(bob, diamond))
        assertEquals(0L, world.count(alice, diamond))
        assertEquals(10L, world.census(diamond), "no duplication, no loss")
    }

    @Test
    fun `unmaking a craft returns only the traced share, not the whole item`() = runTest {
        val steve = player(1)
        world.mint(steve, diamond, 5)
        val looted = world.mint(chest, diamond, 4)
        world.move(chest, steve, diamond, 4)
        world.craft(steve, diamond, 9, diamondBlock, 1)

        val plan = world.rollback(listOf(looted.id), chest).plan

        assertEquals(1, plan.unmakeCount)
        assertEquals(1, plan.takeCount, "one take, for the traced share")
        assertEquals(4L, world.count(chest, diamond))
        assertEquals(5L, world.count(steve, diamond), "Steve keeps exactly his own five")
        assertEquals(0L, world.count(steve, diamondBlock))
        assertEquals(9L, world.census(diamond))
        assertTrue(
            looted.id in world.repo.accountQueue(chest, diamond).map { it.lot.id },
            "the looted lot itself came back, not merely some lot of the same size",
        )
    }
}
