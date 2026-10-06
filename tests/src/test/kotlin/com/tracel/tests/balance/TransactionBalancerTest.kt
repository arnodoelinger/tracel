package com.tracel.tests.balance

import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.balance.TransactionBalancer
import com.tracel.model.flow.FlowKind
import com.tracel.model.flow.isBalanced
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransactionBalancerTest {
    private val balancer = TransactionBalancer()
    private val chest = block(0, 64, 0)
    private val steve = player(1)
    private val alex = player(2)

    @Test
    fun `matched deltas become a single move`() {
        val flows = balancer.balance(listOf(InventoryDelta(chest, diamond, -6), InventoryDelta(steve, diamond, 6)))

        assertTrue(flows.isBalanced())
        val move = flows.single()
        assertEquals(FlowKind.MOVE, move.kind)
        assertEquals(6L, move.quantity.raw)
        assertEquals(chest, move.source)
        assertEquals(steve, move.destination)
    }

    @Test
    fun `a gain with no matching loss is an explicit mint`() {
        val flows = balancer.balance(listOf(InventoryDelta(steve, diamond, 3)))

        assertTrue(flows.isBalanced())
        assertEquals(FlowKind.MINT, flows.single().kind)
        assertEquals(3L, flows.single().quantity.raw)
    }

    @Test
    fun `a loss with no matching gain is an explicit burn`() {
        val flows = balancer.balance(listOf(InventoryDelta(steve, diamond, -2)))

        assertTrue(flows.isBalanced())
        assertEquals(FlowKind.BURN, flows.single().kind)
        assertEquals(2L, flows.single().quantity.raw)
    }

    @Test
    fun `partial overlap is a move plus a burn of the surplus`() {
        val flows = balancer.balance(listOf(InventoryDelta(steve, diamond, -5), InventoryDelta(alex, diamond, 3)))

        assertTrue(flows.isBalanced())
        val byKind = flows.groupBy { it.kind }
        assertEquals(3L, byKind.getValue(FlowKind.MOVE).single().quantity.raw)
        assertEquals(2L, byKind.getValue(FlowKind.BURN).single().quantity.raw)
    }

    @Test
    fun `pairing does not depend on the order the deltas arrive in`() {
        val deltas = listOf(
            InventoryDelta(steve, diamond, -4),
            InventoryDelta(alex, diamond, 3),
            InventoryDelta(chest, diamond, 1),
        )

        assertEquals(balancer.balance(deltas), balancer.balance(deltas.reversed()))
    }

    @Test
    fun `an unmatched loss seen live is unattributed`() {
        val flows = balancer.balance(listOf(InventoryDelta(chest, diamond, -4)))

        assertEquals(HolderId.Sink(SinkKind.UNATTRIBUTED), flows.single().destination)
    }

    @Test
    fun `an unmatched loss found on first sight is a tracking gap`() {
        val flows = balancer.balance(listOf(InventoryDelta(chest, diamond, -4, fromGap = true)))

        assertEquals(HolderId.Sink(SinkKind.UNTRACKED_GAP), flows.single().destination)
    }

    @Test
    fun `an unmatched gain found on first sight is a tracking gap`() {
        val flows = balancer.balance(listOf(InventoryDelta(chest, diamond, 4, fromGap = true)))

        assertEquals(HolderId.Source(SourceKind.UNTRACKED_GAP), flows.single().source)
    }

    @Test
    fun `a gap delta that pairs off is an ordinary move`() {
        val flows = balancer.balance(
            listOf(InventoryDelta(chest, diamond, 4, fromGap = true), InventoryDelta(steve, diamond, -4)),
        )

        val move = flows.single()
        assertEquals(steve, move.source)
        assertEquals(chest, move.destination)
    }

    @Test
    fun `a partly paired gap leaves only the surplus typed as a gap`() {
        val flows = balancer.balance(
            listOf(InventoryDelta(chest, diamond, 10, fromGap = true), InventoryDelta(steve, diamond, -4)),
        )

        val minted = flows.single { it.source is HolderId.Source }
        assertEquals(HolderId.Source(SourceKind.UNTRACKED_GAP), minted.source)
        assertEquals(6L, minted.quantity.raw, "only the part with no counterpart is unexplained")
    }
}
