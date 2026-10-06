package com.tracel.plugin.listener.support.flow

import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.world.WorldId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class DestroyedFlowsTest {
    private val chest = HolderId.Block(WorldId(UUID(0L, 1L)), 10, 64, 10)
    private val diamond = ItemKey("minecraft:diamond")
    private val gold = ItemKey("minecraft:gold_ingot")

    @Test
    fun `what the ledger knew is burned, nothing is minted`() {
        val flows = destroyedFlows(chest, believed = mapOf(diamond to 5L), present = mapOf(diamond to 5L))

        val burn = flows.single()
        assertEquals(FlowKind.BURN, burn.kind)
        assertEquals(Quantity(5L), burn.quantity)
        assertEquals(chest, burn.source)
        assertEquals(DESTROYED_SINK, burn.destination)
    }

    @Test
    fun `a chest nobody watched being filled is minted first so there is something to give back`() {
        val flows = destroyedFlows(chest, believed = emptyMap(), present = mapOf(gold to 12L))

        assertEquals(listOf(FlowKind.MINT, FlowKind.BURN), flows.map { it.kind })
        assertEquals(Quantity(12L), flows[0].quantity)
        assertEquals(Quantity(12L), flows[1].quantity)
    }

    @Test
    fun `only the shortfall is minted when the ledger knew part of it`() {
        val flows = destroyedFlows(chest, believed = mapOf(diamond to 3L), present = mapOf(diamond to 5L))

        assertEquals(Quantity(2L), flows.single { it.kind == FlowKind.MINT }.quantity)
        assertEquals(Quantity(5L), flows.single { it.kind == FlowKind.BURN }.quantity)
    }

    @Test
    fun `stock the ledger believed in but the chest no longer held is burned too`() {
        val flows = destroyedFlows(chest, believed = mapOf(diamond to 4L), present = emptyMap())

        assertTrue(flows.none { it.kind == FlowKind.MINT })
        assertEquals(Quantity(4L), flows.single().quantity)
    }
}
