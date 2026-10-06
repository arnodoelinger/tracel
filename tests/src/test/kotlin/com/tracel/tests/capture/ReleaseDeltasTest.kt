package com.tracel.tests.capture

import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.capture.material.flow.releaseDeltas
import com.tracel.model.item.Quantity
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReleaseDeltasTest {
    @Test
    fun `releaseDeltas is one negative delta per item key currently believed at the holder`() = runTest {
        val harness = LedgerHarness()
        val chest = block(0, 64, 0)

        harness.ledger.mint(chest, diamond, Quantity(5), harness.nextTxn())
        harness.ledger.mint(chest, diamondBlock, Quantity(2), harness.nextTxn())

        assertEquals(
            setOf(InventoryDelta(chest, diamond, -5L), InventoryDelta(chest, diamondBlock, -2L)),
            harness.ledger.releaseDeltas(chest).toSet(),
        )
    }

    @Test
    fun `releaseDeltas is empty for a holder with nothing believed there`() = runTest {
        val harness = LedgerHarness()
        val chest = block(0, 64, 0)

        assertEquals(emptyList<InventoryDelta>(), harness.ledger.releaseDeltas(chest))
    }
}
