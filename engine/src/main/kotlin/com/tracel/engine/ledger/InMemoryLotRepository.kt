package com.tracel.engine.ledger

import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.platform.storage.DirectUnitOfWork
import com.tracel.platform.storage.UnitOfWork
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory [LotRepository]. Everything here is a plain map, so property tests can
 * run thousands of scenarios in milliseconds.
 *
 * Its [UnitOfWork] is [DirectUnitOfWork]: a map has nothing to commit, so batching is a no-op
 * here — a crash mid-operation takes the whole process with it either way.
 */
public class InMemoryLotRepository : LotRepository, UnitOfWork by DirectUnitOfWork {
    private val writer = SingleWriterGuard()

    private val nextLotId = AtomicLong(1)
    private val nextSeq = AtomicLong(1)

    private val lots = mutableMapOf<LotId, Lot>()
    private val edgesByParent = mutableMapOf<LotId, MutableList<LotEdge>>()
    private val edgesByChild = mutableMapOf<LotId, MutableList<LotEdge>>()
    private val queues = mutableMapOf<AccountKey, MutableList<AccountLot>>()
    private val holderOf = mutableMapOf<LotId, HolderId>()

    override suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot {
        writer.checkIn()
        val lot = Lot(LotId(nextLotId.getAndIncrement()), itemKey, quantity, createdBy)
        lots[lot.id] = lot
        return lot
    }

    override suspend fun lot(id: LotId): Lot = lots.getValue(id)

    override suspend fun recordEdge(edge: LotEdge) {
        writer.checkIn()
        edgesByParent.getOrPut(edge.parent) { mutableListOf() }.add(edge)
        edgesByChild.getOrPut(edge.child) { mutableListOf() }.add(edge)
    }

    override suspend fun edgesFrom(lotId: LotId): List<LotEdge> = edgesByParent[lotId].orEmpty()

    override suspend fun edgesInto(lotId: LotId): List<LotEdge> = edgesByChild[lotId].orEmpty()

    override suspend fun accountQueue(holder: HolderId, itemKey: ItemKey, limit: Int): List<AccountLot> =
        queues[AccountKey(holder, itemKey)].orEmpty().sortedBy { it.fifoSeq }.take(limit)

    override suspend fun totalOf(holder: HolderId, itemKey: ItemKey): Long =
        queues[AccountKey(holder, itemKey)].orEmpty().sumOf { it.remaining.raw }

    override suspend fun totalsAt(holder: HolderId): Map<ItemKey, Long> =
        queues.filterKeys { it.holder == holder }
            .entries
            .groupingBy { it.key.itemKey }
            .fold(0L) { total, (_, entries) -> total + entries.sumOf { it.remaining.raw } }

    override suspend fun placementOf(holder: HolderId, lotId: LotId): AccountLot? =
        queues[AccountKey(holder, lot(lotId).itemKey)]?.firstOrNull { it.lot.id == lotId }

    override suspend fun allPlacements(itemKey: ItemKey): List<AccountLot> =
        queues.filterKeys { it.itemKey == itemKey }.values.flatten()

    override suspend fun placementsAt(holder: HolderId): List<AccountLot> =
        queues.filterKeys { it.holder == holder }.values.flatten()

    override suspend fun currentHolderOf(lotId: LotId): HolderId? = holderOf[lotId]

    override suspend fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot {
        writer.checkIn()
        val entry = AccountLot(holder, lot(lotId), quantity, Seq(nextSeq.getAndIncrement()))
        queues.getOrPut(AccountKey(holder, entry.lot.itemKey)) { mutableListOf() }.add(entry)
        holderOf[lotId] = holder
        return entry
    }

    override suspend fun remove(holder: HolderId, lotId: LotId) {
        writer.checkIn()
        val key = AccountKey(holder, lot(lotId).itemKey)
        queues[key]?.removeAll { it.lot.id == lotId }
        holderOf.remove(lotId)
    }

    override suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity) {
        writer.checkIn()
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
