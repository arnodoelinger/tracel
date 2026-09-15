package com.tracel.plugin.listener.support

import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DispenseFlowsTest {
    private val world = WorldId(UUID(0L, 1L))
    private val dispenser = HolderId.Block(world, 10, 64, 10)
    private val diamond = ItemKey("minecraft:diamond")
    private val arrow = ItemKey("minecraft:arrow")

    @Test
    fun `a shot item-block is claimed by the ground item, so it moves and can be taken back`() {
        val ground = HolderId.ItemEntity(UUID(0L, 42L))
        val flows = flowsFor(
            dispenser,
            believed = mapOf(diamond to 1L),
            result = BlockDropCorrelator.ReleaseResult(
                unclaimed = emptyMap(),
                claimed = listOf(BlockDropCorrelator.ClaimedDrop(diamond, 1L, ground)),
            ),
        )

        val move = flows.single()
        assertEquals(FlowKind.MOVE, move.kind)
        assertEquals(dispenser, move.source)
        assertEquals(ground, move.destination)
        assertTrue(flows.none { it.kind == FlowKind.BURN }, "the diamond is on the floor, not destroyed")
    }

    @Test
    fun `a shot arrow claims nothing, so the stack is burned rather than moved`() {
        val flows = flowsFor(
            dispenser,
            believed = mapOf(arrow to 1L),
            result = BlockDropCorrelator.ReleaseResult(unclaimed = mapOf(arrow to 1L), claimed = emptyList()),
        )

        val burn = flows.single()
        assertEquals(FlowKind.BURN, burn.kind)
        assertEquals(dispenser, burn.source)
        assertEquals(HolderId.Sink(SinkKind.UNATTRIBUTED), burn.destination)
    }

    @Test
    fun `a dispenser nobody watched being filled mints what it gives up before moving it`() {
        val ground = HolderId.ItemEntity(UUID(0L, 43L))
        val flows = flowsFor(
            dispenser,
            believed = emptyMap(),
            result = BlockDropCorrelator.ReleaseResult(
                unclaimed = emptyMap(),
                claimed = listOf(BlockDropCorrelator.ClaimedDrop(diamond, 1L, ground)),
            ),
        )

        assertEquals(2, flows.size)
        val mint = flows.first()
        assertEquals(FlowKind.MINT, mint.kind, "the mint has to land before the move that spends it")
        assertEquals(HolderId.Source(SourceKind.WORLDGEN), mint.source)
        assertEquals(dispenser, mint.destination)
        assertEquals(FlowKind.MOVE, flows.last().kind)
    }
}
