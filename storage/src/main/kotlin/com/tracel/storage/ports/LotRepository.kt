package com.tracel.storage.ports

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.engine.ledger.LotRepository as LotRepositoryPort
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.platform.storage.UnitOfWork
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.intern.Interning

/**
 * [LotRepositoryPort] over the packed keyspace.
 *
 * Two things here are worth more than the rest of the file put together.
 *
 * - [totalOf] is a point get, not a sum. The old query summed a holder's whole queue for one
 * item key, so a player who had accumulated four thousand placements cost four thousand rows to
 * answer "how many diamonds do you have" — and the ledger asks that before every withdrawal.
 * The `total` family is a running counter maintained by [place] / [remove] / [replace], and
 * [rebuildTotals] regenerates the whole thing from the placements if it is ever doubted.
 * - [remove] and [replace] are point operations. `placeRev` maps a lot straight to the queue
 * slot it occupies.
 *
 * Lots themselves are cached outright: a lot record is written once at creation and never
 * updated, only its placement moves, so a cache of them cannot go stale.
 */
class LotRepository(
    private val storage: TracelStorage,
    private val counters: Counters,
) : LotRepositoryPort, UnitOfWork by storage {
    private val interning: Interning get() = storage.interning
    private val lots: Cache<LotId, Lot> = Caffeine.newBuilder().maximumSize(100_000).build()

    override suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot {
        val id = counters.nextLotId()
        return storage.write {
            val itemKeyId = interning.internItemKey(this, itemKey)
            put(Keys.lot(id.raw), Records.lot(itemKeyId, quantity.raw, createdBy.raw))
            Lot(id, itemKey, quantity, createdBy).also { lots.put(id, it) }
        }
    }

    override suspend fun lot(id: LotId): Lot = lots.getIfPresent(id) ?: storage.read { readLot(this, id) }

    override suspend fun recordEdge(edge: LotEdge) {
        storage.write {
            val packed = when (edge) {
                is LotEdge.Split -> Records.edge(Records.EDGE_SPLIT, edge.quantity.raw, 0, 0)
                is LotEdge.Transform -> Records.edge(
                    Records.EDGE_TRANSFORM,
                    edge.quantity.raw,
                    edge.craftedBy.raw,
                    interning.internHolder(this, edge.producedAt),
                )

                is LotEdge.Compensate -> Records.edge(
                    Records.EDGE_COMPENSATE,
                    edge.quantity.raw,
                    edge.rollbackJob.raw,
                    0,
                )
            }
            put(Keys.edgeFrom(edge.parent.raw, edge.child.raw), packed)
            put(Keys.edgeInto(edge.child.raw, edge.parent.raw), packed)
        }
    }

    override suspend fun edgesFrom(lotId: LotId): List<LotEdge> = storage.read {
        val out = ArrayList<LotEdge>()
        scan(Keys.edgeFromPrefix(lotId.raw)).use { cursor ->
            while (cursor.next()) {
                out += decodeEdge(this, cursor.value(), parent = lotId.raw, child = KeyReader.u64(cursor.key(), 9))
            }
        }
        out
    }

    override suspend fun edgesInto(lotId: LotId): List<LotEdge> = storage.read {
        val out = ArrayList<LotEdge>()
        scan(Keys.edgeIntoPrefix(lotId.raw)).use { cursor ->
            while (cursor.next()) {
                out += decodeEdge(this, cursor.value(), parent = KeyReader.u64(cursor.key(), 9), child = lotId.raw)
            }
        }
        out
    }

    override suspend fun accountQueue(holder: HolderId, itemKey: ItemKey, limit: Int): List<AccountLot> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyList()
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read emptyList()
        val out = ArrayList<AccountLot>()
        scan(Keys.placePrefix(holderId, itemKeyId)).use { cursor ->
            while (out.size < limit && cursor.next()) {
                out += accountLot(this, holder, cursor.key(), cursor.value())
            }
        }
        out
    }

    override suspend fun totalOf(holder: HolderId, itemKey: ItemKey): Long = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read 0L
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read 0L
        get(Keys.total(holderId, itemKeyId))?.let(Records::asLong) ?: 0L
    }

    override suspend fun totalsAt(holder: HolderId): Map<ItemKey, Long> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyMap()
        val out = LinkedHashMap<ItemKey, Long>()
        scan(Keys.totalPrefix(holderId)).use { cursor ->
            while (cursor.next()) {
                val total = Records.asLong(cursor.value())
                if (total > 0) out[interning.resolveItemKey(this, KeyReader.u32(cursor.key(), 5))] = total
            }
        }
        out
    }

    override suspend fun placementOf(holder: HolderId, lotId: LotId): AccountLot? = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read null
        val reverse = get(Keys.placeRev(lotId.raw, holderId)) ?: return@read null
        val itemKeyId = Records.placementRevItemKeyId(reverse)
        val fifoSeq = Records.placementRevFifoSeq(reverse)
        val key = Keys.place(holderId, itemKeyId, fifoSeq)
        get(key)?.let { accountLot(this, holder, key, it) }
    }

    override suspend fun allPlacements(itemKey: ItemKey): List<AccountLot> = storage.read {
        val itemKeyId = interning.findItemKeyId(this, itemKey) ?: return@read emptyList()
        val out = ArrayList<AccountLot>()
        scan(Keys.placeItemPrefix(itemKeyId)).use { cursor ->
            while (cursor.next()) {
                val holderId = KeyReader.u32(cursor.key(), 5)
                val fifoSeq = KeyReader.u64(cursor.key(), 9)
                val placement = get(Keys.place(holderId, itemKeyId, fifoSeq)) ?: continue
                out += AccountLot(
                    interning.resolveHolder(this, holderId),
                    readLot(this, LotId(Records.placementLotId(placement))),
                    Quantity(Records.placementRemaining(placement)),
                    Seq(fifoSeq),
                )
            }
        }
        out
    }

    override suspend fun placementsAt(holder: HolderId): List<AccountLot> = storage.read {
        val holderId = interning.findHolderId(this, holder) ?: return@read emptyList()
        val out = ArrayList<AccountLot>()
        scan(Keys.placeHolderPrefix(holderId)).use { cursor ->
            while (cursor.next()) out += accountLot(this, holder, cursor.key(), cursor.value())
        }
        out
    }

    override suspend fun currentHolderOf(lotId: LotId): HolderId? = storage.read {
        scan(Keys.placeRevPrefix(lotId.raw)).use { cursor ->
            if (!cursor.next()) null else interning.resolveHolder(this, KeyReader.u32(cursor.key(), 9))
        }
    }

    override suspend fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot {
        val fifoSeq = counters.nextFifoSeq()
        return storage.write {
            val lot = readLot(this, lotId)
            val holderId = interning.internHolder(this, holder)
            val itemKeyId = interning.internItemKey(this, lot.itemKey)
            put(Keys.place(holderId, itemKeyId, fifoSeq.raw), Records.placement(lotId.raw, quantity.raw))
            put(Keys.placeRev(lotId.raw, holderId), Records.placementRev(itemKeyId, fifoSeq.raw))
            put(Keys.placeItem(itemKeyId, holderId, fifoSeq.raw), EMPTY)
            addToTotal(this, holderId, itemKeyId, quantity.raw)
            AccountLot(holder, lot, quantity, fifoSeq)
        }
    }

    override suspend fun remove(holder: HolderId, lotId: LotId) {
        storage.write {
            val holderId = interning.findHolderId(this, holder) ?: return@write
            val reverseKey = Keys.placeRev(lotId.raw, holderId)
            val reverse = get(reverseKey) ?: return@write
            val itemKeyId = Records.placementRevItemKeyId(reverse)
            val fifoSeq = Records.placementRevFifoSeq(reverse)
            val placementKey = Keys.place(holderId, itemKeyId, fifoSeq)
            val remaining = get(placementKey)?.let(Records::placementRemaining) ?: 0L

            delete(placementKey)
            delete(reverseKey)
            delete(Keys.placeItem(itemKeyId, holderId, fifoSeq))
            addToTotal(this, holderId, itemKeyId, -remaining)
        }
    }

    override suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity) {
        storage.write {
            val holderId = interning.findHolderId(this, holder) ?: error("no placement of $retiredLotId at $holder")
            val reverseKey = Keys.placeRev(retiredLotId.raw, holderId)
            val reverse = get(reverseKey) ?: error("no placement of $retiredLotId at $holder")
            val itemKeyId = Records.placementRevItemKeyId(reverse)
            val fifoSeq = Records.placementRevFifoSeq(reverse)
            val placementKey = Keys.place(holderId, itemKeyId, fifoSeq)
            val previous = get(placementKey)?.let(Records::placementRemaining) ?: 0L

            // The replacement keeps the retired lot's queue slot: for everyone who comes after,
            // it is still the oldest thing in this account.
            put(placementKey, Records.placement(newLotId.raw, remaining.raw))
            delete(reverseKey)
            put(Keys.placeRev(newLotId.raw, holderId), Records.placementRev(itemKeyId, fifoSeq))
            addToTotal(this, holderId, itemKeyId, remaining.raw - previous)
        }
    }

    fun forget() {
        lots.invalidateAll()
    }

    private fun addToTotal(unit: StorageUnit, holderId: Int, itemKeyId: Int, delta: Long) {
        if (delta == 0L) return
        val key = Keys.total(holderId, itemKeyId)
        val updated = (unit.get(key)?.let(Records::asLong) ?: 0L) + delta
        if (updated == 0L) unit.delete(key) else unit.put(key, Records.long(updated))
    }

    private fun readLot(unit: StorageUnit, id: LotId): Lot {
        lots.getIfPresent(id)?.let { return it }
        val value = unit.get(Keys.lot(id.raw)) ?: error("lot $id does not exist")
        return Lot(
            id,
            interning.resolveItemKey(unit, Records.lotItemKeyId(value)),
            Quantity(Records.lotQuantity(value)),
            TxnId(Records.lotCreatedBy(value)),
        ).also { lots.put(id, it) }
    }

    private fun accountLot(
        unit: StorageUnit,
        holder: HolderId,
        placementKey: ByteArray,
        placement: java.lang.foreign.MemorySegment,
    ): AccountLot = AccountLot(
        holder,
        readLot(unit, LotId(Records.placementLotId(placement))),
        Quantity(Records.placementRemaining(placement)),
        Seq(KeyReader.u64(placementKey, 9)),
    )

    private fun decodeEdge(
        unit: StorageUnit,
        value: java.lang.foreign.MemorySegment,
        parent: Long,
        child: Long,
    ): LotEdge {
        val quantity = Quantity(Records.edgeQuantity(value))
        return when (val kind = Records.edgeKind(value)) {
            Records.EDGE_SPLIT -> LotEdge.Split(LotId(child), LotId(parent), quantity)
            Records.EDGE_TRANSFORM -> LotEdge.Transform(
                LotId(child),
                LotId(parent),
                quantity,
                TxnId(Records.edgeReference(value)),
                interning.resolveHolder(unit, Records.edgeProducedAt(value)),
            )

            Records.EDGE_COMPENSATE -> LotEdge.Compensate(
                LotId(child),
                LotId(parent),
                quantity,
                RollbackJobId(Records.edgeReference(value)),
            )

            else -> error("unrecognized lot edge kind: $kind")
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
