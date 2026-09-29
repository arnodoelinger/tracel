package com.tracel.plugin.rollback.survey

import com.tracel.annotations.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class MintedStraightThroughTest {
    private val pig = HolderId.Entity(UUID(3L, 3L))
    private val ground = HolderId.ItemEntity(UUID(4L, 4L))
    private val steve = HolderId.Player(UUID(0L, 2L))
    private val worldgen = HolderId.Source(SourceKind.WORLDGEN)
    private val nowhere = HolderId.Source(SourceKind.UNATTRIBUTED)
    private val chest = HolderId.Block(WorldId(UUID(0L, 1L)), 0, 64, 0)
    private val porkchop = ItemKey("PORKCHOP", null)
    private val saddle = ItemKey("SADDLE", null)

    @Test
    fun `a death drop's mob is not a place its meat goes back to`() {
        val txn = txn(
            flow(porkchop, 2, worldgen, pig, FlowKind.MINT),
            flow(porkchop, 2, pig, ground, FlowKind.MOVE),
        )
        assertEquals(mapOf<HolderId, HolderId>(pig to worldgen), txn.mintedStraightThrough())
    }

    @Test
    fun `rooting a death drop at the source destroys it instead of spilling it`() {
        val lot = com.tracel.model.id.LotId(1)
        val txn = txn(
            flow(porkchop, 2, worldgen, pig, FlowKind.MINT),
            flow(porkchop, 2, pig, ground, FlowKind.MOVE),
        )
        val passedThrough = txn.mintedStraightThrough()
        val roots = mutableMapOf<com.tracel.model.id.LotId, HolderId>()
        for (flow in txn.flows) roots.rootedAt(lot, passedThrough[flow.source] ?: flow.source)
        assertEquals(worldgen, roots[lot], "the meat came from nowhere and goes back to nowhere")
    }

    @Test
    fun `a death drop picked up or tossed again is still rooted at its mint`() {
        val lot = com.tracel.model.id.LotId(1)
        val pile2 = HolderId.ItemEntity(UUID(5L, 5L))
        val kill = txn(
            flow(porkchop, 2, worldgen, pig, FlowKind.MINT),
            flow(porkchop, 2, pig, ground, FlowKind.MOVE),
        )
        val pickup = txn(flow(porkchop, 2, ground, steve, FlowKind.MOVE))
        val toss = txn(flow(porkchop, 2, steve, pile2, FlowKind.MOVE))
        val roots = mutableMapOf<com.tracel.model.id.LotId, HolderId>()
        for (txn in listOf(toss, pickup, kill)) {
            val passedThrough = txn.mintedStraightThrough()
            for (flow in txn.flows) roots.rootedByFlow(lot, flow.source, passedThrough)
        }
        assertEquals(worldgen, roots[lot], "not the pile it was picked up from: the mob comes back, its meat does not spill")
    }

    @Test
    fun `a hull minted into and taken from over two transactions keeps the hull`() {
        val fill = txn(flow(saddle, 1, nowhere, pig, FlowKind.MINT))
        val loot = txn(flow(saddle, 1, pig, steve, FlowKind.MOVE))
        assertTrue(fill.mintedStraightThrough().isEmpty(), "one transaction is not a pass-through")
        assertTrue(loot.mintedStraightThrough().isEmpty(), "one transaction is not a pass-through")
    }

    @Test
    fun `one transaction touching a hull with two different items is not a pass-through`() {
        val txn = txn(
            flow(saddle, 1, nowhere, pig, FlowKind.MINT),
            flow(porkchop, 1, pig, steve, FlowKind.MOVE),
        )
        assertTrue(txn.mintedStraightThrough().isEmpty(), "the pass-through is per item, not per holder")
    }

    @Test
    fun `a container filled and emptied in one transaction stays a place`() {
        val txn = txn(
            flow(porkchop, 2, worldgen, chest, FlowKind.MINT),
            flow(porkchop, 2, chest, steve, FlowKind.MOVE),
        )
        assertTrue(txn.mintedStraightThrough().isEmpty(), "a chest has slots; a pig does not")
    }

    private fun flow(item: ItemKey, amount: Long, from: HolderId, to: HolderId, kind: FlowKind) =
        Flow(item, Quantity(amount), from, to, kind)

    private fun txn(vararg flows: Flow) =
        Transaction(TxnId(1), Seq(1), 0L, CauseKind.WORLD, null, flows.toList())
}
