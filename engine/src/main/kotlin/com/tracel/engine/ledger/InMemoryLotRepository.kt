package com.tracel.engine.ledger

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge

/**
 * In-memory [LotRepository]. Everything here is a plain map, so property tests can
 * run thousands of scenarios in milliseconds.
 */
public class InMemoryLotRepository : LotRepository {
    private var nextLotIdRaw = 1L
    private var nextSeqRaw = 1L

    private val lots = mutableMapOf<LotId, Lot>()
    private val edgesByParent = mutableMapOf<LotId, MutableList<LotEdge>>()
    private val edgesByChild = mutableMapOf<LotId, MutableList<LotEdge>>()
    private val queues = mutableMapOf<AccountKey, MutableList<AccountLot>>()
    private val holderOf = mutableMapOf<LotId, HolderId>()

    override fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot {
        val lot = Lot(LotId(nextLotIdRaw++), itemKey, quantity, createdBy)
        lots[lot.id] = lot
        return lot
    }

    override fun lot(id: LotId): Lot = lots.getValue(id)

    override fun recordEdge(edge: LotEdge) {
        edgesByParent.getOrPut(edge.parent) { mutableListOf() }.add(edge)
        edgesByChild.getOrPut(edge.child) { mutableListOf() }.add(edge)
    }

    override fun edgesFrom(lotId: LotId): List<LotEdge> = edgesByParent[lotId].orEmpty()

    override fun edgesInto(lotId: LotId): List<LotEdge> = edgesByChild[lotId].orEmpty()

    override fun accountQueue(holder: HolderId, itemKey: ItemKey): List<AccountLot> =
        queues[AccountKey(holder, itemKey)].orEmpty().sortedBy { it.fifoSeq }

    override fun allPlacements(itemKey: ItemKey): List<AccountLot> =
        queues.filterKeys { it.itemKey == itemKey }.values.flatten()

    override fun currentHolderOf(lotId: LotId): HolderId? = holderOf[lotId]

    override fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot {
        val entry = AccountLot(holder, lot(lotId), quantity, Seq(nextSeqRaw++))
        queues.getOrPut(AccountKey(holder, entry.lot.itemKey)) { mutableListOf() }.add(entry)
        holderOf[lotId] = holder
        return entry
    }

    override fun remove(holder: HolderId, lotId: LotId) {
        val key = AccountKey(holder, lot(lotId).itemKey)
        queues[key]?.removeAll { it.lot.id == lotId }
        holderOf.remove(lotId)
    }

    override fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity) {
        val key = AccountKey(holder, lot(retiredLotId).itemKey)
        val queue = queues.getValue(key)
        val index = queue.indexOfFirst { it.lot.id == retiredLotId }
        check(index >= 0) { "no placement of $retiredLotId at $holder" }
        queue[index] = AccountLot(holder, lot(newLotId), remaining, queue[index].fifoSeq)
        holderOf.remove(retiredLotId)
        holderOf[newLotId] = holder
    }

    private data class AccountKey(val holder: HolderId, val itemKey: ItemKey)
}
