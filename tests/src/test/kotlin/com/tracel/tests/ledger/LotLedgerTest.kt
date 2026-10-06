package com.tracel.tests.ledger

import com.tracel.model.item.Quantity
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import com.tracel.tests.support.assertFails
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LotLedgerTest {
    @Test
    fun `totalsAt sums every item key currently placed at a holder`() = runTest {
        val harness = LedgerHarness()
        val chest = block(0, 64, 0)

        harness.ledger.mint(chest, diamond, Quantity(3), harness.nextTxn())
        harness.ledger.mint(chest, diamond, Quantity(2), harness.nextTxn())
        harness.ledger.mint(chest, diamondBlock, Quantity(1), harness.nextTxn())

        assertEquals(mapOf(diamond to Quantity(5), diamondBlock to Quantity(1)), harness.ledger.totalsAt(chest))
    }

    @Test
    fun `totalsAt drops an item key once it is withdrawn to zero`() = runTest {
        val harness = LedgerHarness()
        val chest = block(0, 64, 0)

        harness.ledger.mint(chest, diamond, Quantity(4), harness.nextTxn())
        harness.ledger.withdraw(chest, diamond, Quantity(4), harness.nextTxn())

        assertEquals(emptyMap<Any, Any>(), harness.ledger.totalsAt(chest))
    }

    @Test
    fun `totalsAt only reports the requested holder`() = runTest {
        val harness = LedgerHarness()
        val chest = block(0, 64, 0)
        val other = block(1, 64, 0)

        harness.ledger.mint(chest, diamond, Quantity(1), harness.nextTxn())
        harness.ledger.mint(other, diamond, Quantity(9), harness.nextTxn())

        assertEquals(mapOf(diamond to Quantity(1)), harness.ledger.totalsAt(chest))
    }

    @Test
    fun `a withdrawal larger than the account holds destroys nothing`() = runTest {
        // The shortfall check used to run (!) after the loop that retires lots, so a withdrawal of
        // more than an account held removed everything it could reach and only then threw — and
        // since every caller treats that throw as the ordinary "untracked material" case and
        // carries on, those units were silently destroyed with no transaction logged to say so.
        val harness = LedgerHarness()
        val chest = block(0, 64, 0)
        harness.ledger.mint(chest, diamond, Quantity(4), harness.nextTxn())

        assertFails<IllegalStateException> {
            harness.ledger.withdraw(chest, diamond, Quantity(10), harness.nextTxn())
        }

        assertEquals(mapOf(diamond to Quantity(4)), harness.ledger.totalsAt(chest), "the account must be untouched")
        assertEquals(4L, harness.ledger.census(diamond), "nothing may disappear from the world")
    }

    @Test
    fun `a failed withdrawal spanning several lots leaves every one of them in place`() = runTest {
        // The multi-lot case is the one that actually lost material: the loop retired lot after
        // lot on its way to a total it could never reach.
        val harness = LedgerHarness()
        val chest = block(0, 64, 0)
        harness.ledger.mint(chest, diamond, Quantity(3), harness.nextTxn())
        harness.ledger.mint(chest, diamond, Quantity(3), harness.nextTxn())

        assertFails<IllegalStateException> {
            harness.ledger.withdraw(chest, diamond, Quantity(7), harness.nextTxn())
        }

        assertEquals(mapOf(diamond to Quantity(6)), harness.ledger.totalsAt(chest))
        assertEquals(2, harness.repo.accountQueue(chest, diamond).size, "both lots must still be placed")
    }

    @Test
    fun `draining to several places hands each the oldest lots, exactly as withdrawing one at a time did`() = runTest {
        val world = LedgerHarness()
        val escrow = block(0, 64, 0)
        val first = player(1)
        val second = player(2)

        val oldest = world.ledger.mint(escrow, diamond, Quantity(2), world.nextTxn())
        world.ledger.mint(escrow, diamond, Quantity(4), world.nextTxn())

        val handed = world.ledger.drain(
            escrow,
            diamond,
            listOf(first to 3L, second to 3L),
            world.nextTxn(),
        )

        assertEquals(listOf(first, second), handed.map { it.first }, "each destination is served in the order given")
        assertEquals(3L, handed[0].second.sumOf { it.quantity.raw })
        assertEquals(3L, handed[1].second.sumOf { it.quantity.raw })
        assertEquals(
            oldest.id,
            handed[0].second.first().lotId,
            "the first destination gets the oldest lot — that is what FIFO means and a rollback depends on it",
        )
        assertTrue(handed[1].second.none { it.lotId == oldest.id }, "and the second cannot be given it twice")
        assertNull(world.ledger.totalAt(escrow, diamond), "escrow is left empty")
    }
}
