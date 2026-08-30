package com.tracel.tests.property

import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.balance.TransactionBalancer
import com.tracel.model.flow.FlowKind
import com.tracel.model.flow.isBalanced
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ConservationTest {

    @Test
    fun `matched deltas become a single balanced move`() = runTest {
        val flows = TransactionBalancer().balance(
            listOf(InventoryDelta(block(0, 64, 0), diamond, -6), InventoryDelta(player(1), diamond, 6)),
        )
        assertTrue(flows.isBalanced())
        assertEquals(1, flows.size)
        assertEquals(FlowKind.MOVE, flows.single().kind)
        assertEquals(6L, flows.single().quantity.raw)
    }

    @Test
    fun `a gain with no matching loss becomes an explicit mint`() = runTest {
        val flows = TransactionBalancer().balance(listOf(InventoryDelta(player(1), diamond, 3)))
        assertTrue(flows.isBalanced())
        assertEquals(FlowKind.MINT, flows.single().kind)
        assertEquals(3L, flows.single().quantity.raw)
    }

    @Test
    fun `a loss with no matching gain becomes an explicit burn`() = runTest {
        val flows = TransactionBalancer().balance(listOf(InventoryDelta(player(1), diamond, -2)))
        assertTrue(flows.isBalanced())
        assertEquals(FlowKind.BURN, flows.single().kind)
        assertEquals(2L, flows.single().quantity.raw)
    }

    @Test
    fun `partial overlap splits cleanly into a move and a leftover burn`() = runTest {
        // Player A has lost 5, player B has gained only 3 — 3 match as a move, and the remaining 2 losses become an
        // honest "BURN", not silently lost in the difference.
        val flows = TransactionBalancer().balance(
            listOf(InventoryDelta(player(1), diamond, -5), InventoryDelta(player(2), diamond, 3)),
        )
        assertTrue(flows.isBalanced())
        val byKind = flows.groupBy { it.kind }
        assertEquals(3L, byKind.getValue(FlowKind.MOVE).single().quantity.raw)
        assertEquals(2L, byKind.getValue(FlowKind.BURN).single().quantity.raw)
    }
}
