package com.tracel.tests.ledger

import com.tracel.model.item.Quantity
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LotLedgerTest {
    private val world = LedgerHarness()
    private val chest = block(0, 64, 0)

    @Test
    fun `totalsAt sums every item key placed at a holder`() = runTest {
        world.mint(chest, diamond, 3)
        world.mint(chest, diamond, 2)
        world.mint(chest, diamondBlock, 1)

        assertEquals(mapOf(diamond to Quantity(5), diamondBlock to Quantity(1)), world.ledger.totalsAt(chest))
    }

    @Test
    fun `totalsAt drops an item key once it is withdrawn to zero`() = runTest {
        world.mint(chest, diamond, 4)
        world.ledger.withdraw(chest, diamond, Quantity(4), world.nextTxn())

        assertEquals(emptyMap<Any, Any>(), world.ledger.totalsAt(chest))
    }

    @Test
    fun `totalsAt reports only the requested holder`() = runTest {
        world.mint(chest, diamond, 1)
        world.mint(block(1, 64, 0), diamond, 9)

        assertEquals(mapOf(diamond to Quantity(1)), world.ledger.totalsAt(chest))
    }

    @Test
    fun `withdraw takes the oldest lot first`() = runTest {
        val older = world.mint(chest, diamond, 3)
        world.mint(chest, diamond, 5)

        val taken = world.ledger.withdraw(chest, diamond, Quantity(3), world.nextTxn())

        assertEquals(listOf(older.id), taken.map { it.lotId })
        assertEquals(5L, world.count(chest, diamond))
    }

    @Test
    fun `a partial withdraw splits the oldest lot and leaves the remainder oldest`() = runTest {
        world.mint(chest, diamond, 10)
        world.mint(chest, diamond, 4)

        world.ledger.withdraw(chest, diamond, Quantity(3), world.nextTxn())
        assertEquals(listOf(7L, 4L), world.repo.accountQueue(chest, diamond).map { it.remaining.raw })

        val second = world.ledger.withdraw(chest, diamond, Quantity(7), world.nextTxn())
        assertEquals(7L, second.single().quantity.raw)
        assertEquals(4L, world.count(chest, diamond))
    }

    @Test
    fun `a withdrawal larger than the account holds destroys nothing`() = runTest {
        world.mint(chest, diamond, 4)

        assertThrows<IllegalStateException> { world.ledger.withdraw(chest, diamond, Quantity(10), world.nextTxn()) }

        assertEquals(mapOf(diamond to Quantity(4)), world.ledger.totalsAt(chest))
        assertEquals(4L, world.census(diamond))
    }

    @Test
    fun `a failed withdrawal spanning several lots leaves every one of them placed`() = runTest {
        world.mint(chest, diamond, 3)
        world.mint(chest, diamond, 3)

        assertThrows<IllegalStateException> { world.ledger.withdraw(chest, diamond, Quantity(7), world.nextTxn()) }

        assertEquals(mapOf(diamond to Quantity(6)), world.ledger.totalsAt(chest))
        assertEquals(2, world.repo.accountQueue(chest, diamond).size)
    }

    @Test
    fun `drain serves each destination in the order given, oldest lots first`() = runTest {
        val first = player(1)
        val second = player(2)
        val oldest = world.mint(chest, diamond, 2)
        world.mint(chest, diamond, 4)

        val handed = world.ledger.drain(chest, diamond, listOf(first to 3L, second to 3L), world.nextTxn())

        assertEquals(listOf(first, second), handed.map { it.first })
        assertEquals(listOf(3L, 3L), handed.map { (_, portions) -> portions.sumOf { it.quantity.raw } })
        assertEquals(oldest.id, handed[0].second.first().lotId, "FIFO: the first destination gets the oldest lot")
        assertTrue(handed[1].second.none { it.lotId == oldest.id }, "and the second cannot be given it again")
        assertNull(world.ledger.totalAt(chest, diamond), "the source is left empty")
    }

    @Test
    fun `relocate keeps fifo order so the next withdrawal still takes the older lot`() = runTest {
        val to = block(0, 64, 1)
        val older = world.mint(chest, diamond, 1)
        world.mint(to, diamond, 1)

        world.repo.relocate(chest, to)

        assertEquals(older.id, world.ledger.withdraw(to, diamond, Quantity(1), world.nextTxn()).single().lotId)
    }
}
