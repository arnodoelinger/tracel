package com.tracel.plugin.rollback.survey

import com.tracel.model.transaction.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class PairedGapMintsTest {
    private val player = HolderId.Player(UUID(1L, 1L))
    private val stand = HolderId.Entity(UUID(2L, 2L))
    private val tunic = ItemKey("LEATHER_CHESTPLATE", null)
    private val gap = HolderId.Source(SourceKind.UNTRACKED_GAP)
    private val unattributed = HolderId.Sink(SinkKind.UNATTRIBUTED)

    private var seq = 0L

    private fun txn(at: Long, flow: Flow, lot: Long, quantity: Long = flow.quantity.raw): Transaction {
        seq++
        return Transaction(
            TxnId(seq), Seq(seq), at, CauseKind.PLAYER_ACTION, null, listOf(flow),
            listOf(FlowLot(0, LotId(lot), Quantity(quantity))),
        )
    }

    private fun burn(at: Long, lot: Long) =
        txn(at, Flow(tunic, Quantity(1), player, unattributed, FlowKind.BURN), lot)

    private fun mint(at: Long, lot: Long, key: ItemKey = tunic, quantity: Long = 1) =
        txn(at, Flow(key, Quantity(quantity), gap, stand, FlowKind.MINT), lot)

    private fun pair(vararg txns: Transaction) = pairedGapMints(txns.toList()) { it.lots }

    @Test
    fun `a gap mint a moment after a burn of the same item is its other half`() {
        val out = pair(burn(1_000, 10), mint(1_040, 11))
        assertEquals(mapOf<LotId, HolderId>(LotId(11) to gap), out)
    }

    @Test
    fun `a mint long after the burn belongs to something else`() {
        assertTrue(pair(burn(1_000, 10), mint(60_000, 11)).isEmpty())
    }

    @Test
    fun `another item is not the other half`() {
        assertTrue(pair(burn(1_000, 10), mint(1_040, 11, ItemKey("DIAMOND", null))).isEmpty())
    }

    @Test
    fun `a mint with no burn beside it is left alone`() {
        assertTrue(pair(mint(1_000, 11)).isEmpty())
    }

    @Test
    fun `one burn pairs one mint, and a lot bigger than the burn is not cut`() {
        val out = pair(burn(1_000, 10), mint(1_010, 11), mint(1_020, 12), mint(1_030, 13, quantity = 5))
        assertEquals(1, out.size)
        assertEquals(setOf(LotId(11)), out.keys)
    }
}
