package com.tracel.plugin.lookup

import com.tracel.annotations.CauseKind
import com.tracel.engine.provenance.FateNode
import com.tracel.engine.provenance.ProvenanceNode
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LookupRenderTest {
    private val world = WorldId(UUID(0L, 1L))
    private val chest = HolderId.Block(world, 1, 2, 3)
    private val steve = HolderId.Player(UUID(0L, 2L))
    private val diamond = ItemKey("minecraft:diamond")

    @Test
    fun `describeHolder covers every HolderId variant without throwing`() {
        val holders = listOf(
            chest,
            HolderId.PlacedBlock(world, 1, 2, 3),
            steve,
            HolderId.Entity(UUID(0L, 3L)),
            HolderId.ItemEntity(UUID(0L, 4L)),
            HolderId.Source(SourceKind.CRAFT),
            HolderId.Sink(SinkKind.LAVA),
        )
        for (holder in holders) assertTrue(describeHolder(holder).isNotBlank())
    }

    @Test
    fun `origin tree renders one line per lot, indented by depth`() {
        val root = ProvenanceNode(LotId(3), diamond, TxnId(30), emptyList())
        val other = ProvenanceNode(LotId(4), diamond, TxnId(31), emptyList())
        val merged = ProvenanceNode(LotId(2), diamond, TxnId(20), listOf(root, other))

        val lines = renderOrigin(merged)

        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("2x lot"))
        assertTrue(lines[1].trim().startsWith("<-"))
        assertTrue(lines[1].startsWith("  "))
    }

    @Test
    fun `fate tree shows the current holder only on a leaf`() {
        val leaf = FateNode(LotId(5), diamond, steve, emptyList())
        val root = FateNode(LotId(2), diamond, null, listOf(leaf))

        val lines = renderFate(root)

        assertEquals(2, lines.size)
        assertTrue(!lines[0].contains(" @ "))
        assertTrue(lines[1].contains(" @ "))
    }

    @Test
    fun `countUnits sums quantities, filtered by item when given`() {
        val txn = Transaction(
            TxnId(1), Seq(1), 0L, CauseKind.PLAYER_ACTION, null,
            listOf(
                Flow(diamond, Quantity(5), chest, steve, FlowKind.MOVE),
                Flow(ItemKey("minecraft:iron_ingot"), Quantity(3), chest, steve, FlowKind.MOVE),
            ),
        )

        assertEquals(8L, countUnits(listOf(txn)))
        assertEquals(5L, countUnits(listOf(txn), "minecraft:diamond"))
    }

    @Test
    fun `lookup result rendering skips a transaction with no matching flow`() {
        val txn = Transaction(TxnId(1), Seq(1), 0L, CauseKind.PLAYER_ACTION, null, listOf(Flow(diamond, Quantity(1), chest, steve, FlowKind.MOVE)))
        assertEquals(emptyList<String>(), renderLookupResult(txn, item = "minecraft:iron_ingot"))
        assertEquals(2, renderLookupResult(txn, item = "minecraft:diamond").size)
    }
}
