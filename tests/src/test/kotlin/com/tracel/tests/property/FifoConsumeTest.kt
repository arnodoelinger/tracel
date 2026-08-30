package com.tracel.tests.property

import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.LedgerHarness
import com.tracel.tests.support.assertFails
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class FifoConsumeTest {
    @Test
    fun `withdraw takes the oldest lot first`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val older = world.ledger.mint(chest, diamond, Quantity(3), world.nextTxn())
        world.ledger.mint(chest, diamond, Quantity(5), world.nextTxn())

        val taken = world.ledger.withdraw(chest, diamond, Quantity(3), world.nextTxn())
        assertEquals(listOf(older.id), taken.map { it.lotId })
        assertEquals(5L, world.ledger.totalAt(chest, diamond)?.raw)
    }

    @Test
    fun `a partial take splits the oldest lot and leaves the remainder oldest`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.mint(chest, diamond, Quantity(4), world.nextTxn())

        world.ledger.withdraw(chest, diamond, Quantity(3), world.nextTxn())
        val rest = world.repo.accountQueue(chest, diamond)
        assertEquals(2, rest.size)
        assertEquals(7L, rest[0].remaining.raw)
        assertEquals(4L, rest[1].remaining.raw)

        val second = world.ledger.withdraw(chest, diamond, Quantity(7), world.nextTxn())
        assertEquals(1, second.size)
        assertEquals(7L, second.single().quantity.raw)
        assertEquals(4L, world.ledger.totalAt(chest, diamond)?.raw)
    }

    @Test
    fun `a shortfall withdraw is a no-op`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val lot = world.ledger.mint(chest, diamond, Quantity(4), world.nextTxn())

        assertFails<IllegalStateException> {
            world.ledger.withdraw(chest, diamond, Quantity(9), world.nextTxn())
        }
        assertEquals(lot.id, world.repo.accountQueue(chest, diamond).single().lot.id)
        assertEquals(4L, world.ledger.census(diamond))
    }

    @Test
    fun `drain hands destinations oldest-first in owed order`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val a = HolderId.Player(UUID(0L, 10L))
        val b = HolderId.Player(UUID(0L, 11L))
        val first = world.ledger.mint(chest, diamond, Quantity(2), world.nextTxn())
        val second = world.ledger.mint(chest, diamond, Quantity(3), world.nextTxn())

        val drained = world.ledger.drain(
            chest,
            diamond,
            listOf(a to 2L, b to 3L),
            world.nextTxn(),
        )
        assertEquals(listOf(first.id), drained[0].second.map { it.lotId })
        assertEquals(listOf(second.id), drained[1].second.map { it.lotId })
        assertNull(world.ledger.totalAt(chest, diamond))
    }

    @Test
    fun `relocate keeps fifo order so the next consume still takes the older lot`() = runTest {
        val world = LedgerHarness()
        val from = block(0, 64, 0)
        val to = block(0, 64, 1)
        val older = world.ledger.mint(from, diamond, Quantity(1), world.nextTxn())
        world.ledger.mint(to, diamond, Quantity(1), world.nextTxn())
        world.repo.relocate(from, to)

        val taken = world.ledger.withdraw(to, diamond, Quantity(1), world.nextTxn())
        assertEquals(older.id, taken.single().lotId)
    }
}
