package com.tracel.engine.ledger.repository.memory

import com.tracel.annotations.Consume
import com.tracel.annotations.Reads
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext
import com.tracel.engine.ledger.LotPortion
import com.tracel.engine.ledger.repository.LotRepository
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.log.Seq
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.transaction.TxnId
import com.tracel.platform.concurrency.SingleWriterGuard
import com.tracel.platform.storage.UnitOfWork
import kotlinx.atomicfu.atomic
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * In-memory [LotRepository], the reference the stored one is checked against.
 *
 * Everything it knows is one immutable [RepositoryState]. A write builds the next state and publishes it whole, so
 * readers on any thread see either the old picture or the new one, never a half.
 */
// TODO: refactor ts
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryLotRepository : LotRepository, UnitOfWork {
    private val writer = SingleWriterGuard()

    private val nextLotId = atomic(1L)
    private val nextSeq = atomic(1L)
    private val state = AtomicReference(RepositoryState())
    private val changes = atomic(0L)
    private var depth = 0

    @Reads
    override suspend fun version(): Long = changes.value

    override suspend fun <T> atomically(block: suspend () -> T): T {
        writer.checkIn()
        val before = state.get()
        depth++
        try {
            return block()
        } catch (failure: Throwable) {
            if (depth == 1) state.lazySet(before)
            throw failure
        } finally {
            depth--
        }
    }

    /** Publishes [next] and counts the change. */
    private fun commit(next: RepositoryState) {
        state.set(next)
        changes.incrementAndGet()
    }

    override suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot {
        writer.checkIn()
        val lot = Lot(LotId(nextLotId.getAndIncrement()), itemKey, quantity, createdBy)
        val s = state.get()
        commit(s.copy(lots = s.lots.putting(lot.id, lot)))
        return lot
    }

    @Reads
    override suspend fun lot(id: LotId): Lot = state.get().lots.getValue(id)

    override suspend fun recordEdge(edge: LotEdge) {
        writer.checkIn()
        commit(state.get().putEdge(edge))
    }

    override suspend fun removeEdge(parent: LotId, child: LotId) {
        writer.checkIn()
        commit(state.get().removeEdge(parent, child))
    }

    @Reads
    override suspend fun edgesFrom(lotId: LotId): List<LotEdge> = snapshotEdges(state.get().edgesByParent[lotId])

    @Reads
    override suspend fun edgesInto(lotId: LotId): List<LotEdge> = snapshotEdges(state.get().edgesByChild[lotId])

    @Reads
    override suspend fun findCompensateEdge(originalLotId: LotId, job: RollbackJobId): LotEdge.Compensate? {
        val edges = state.get().edgesByParent[originalLotId] ?: return null
        for (edge in edges.values) {
            if (edge is LotEdge.Compensate && edge.rollbackJob == job) return edge
        }
        return null
    }

    @Reads
    override suspend fun accountQueue(holder: HolderId, itemKey: ItemKey, limit: Int): List<AccountLot> {
        if (limit <= 0) return emptyList()
        val s = state.get()
        val key = s.keyOrNull(holder, itemKey) ?: return emptyList()
        val queue = s.queues[key] ?: return emptyList()
        if (queue.isEmpty()) return emptyList()
        if (limit >= queue.size) return queue
        return queue.subList(0, min(limit, queue.size))
    }

    @Consume
    override suspend fun takeFifo(
        holder: HolderId,
        itemKey: ItemKey,
        quantity: Quantity,
        txn: TxnId,
    ): List<LotPortion> {
        writer.checkIn()
        val s = state.get()
        val key = s.keyOrNull(holder, itemKey)
        val queue = FifoQueue.from(key?.let { s.queues[it] })
        var stillNeeded = quantity.raw
        val taken = ArrayList<LotPortion>(4)
        val draft = Draft(s)
        while (stillNeeded > 0) {
            val entry = queue.peekFirst()
            check(entry != null && key != null) {
                "insufficient balance at $holder for $itemKey: needed ${quantity.raw}, short by $stillNeeded"
            }
            stillNeeded -= takeFromHead(draft, holder, queue, entry, stillNeeded, txn, taken)
        }
        if (key != null) commit(draft.commit(s, holder, itemKey, key, queue))
        return taken
    }

    @Consume
    override suspend fun drainFifo(
        holder: HolderId,
        itemKey: ItemKey,
        owed: List<Pair<HolderId, Long>>,
        txn: TxnId,
    ): List<Pair<HolderId, List<LotPortion>>> {
        writer.checkIn()
        val s = state.get()
        val key = s.keyOrNull(holder, itemKey)
        val queue = FifoQueue.from(key?.let { s.queues[it] })
        val out = ArrayList<Pair<HolderId, List<LotPortion>>>(owed.size)
        if (key == null) return out
        val draft = Draft(s)
        for (i in owed.indices) {
            val dest = owed[i]
            var stillNeeded = dest.second
            var taken: ArrayList<LotPortion>? = null
            while (stillNeeded > 0) {
                val entry = queue.peekFirst() ?: break
                val sink = taken ?: ArrayList<LotPortion>(4).also { taken = it }
                stillNeeded -= takeFromHead(draft, holder, queue, entry, stillNeeded, txn, sink)
            }
            val got = taken
            if (!got.isNullOrEmpty()) out.add(dest.first to got)
        }
        commit(draft.commit(s, holder, itemKey, key, queue))
        return out
    }

    @Reads
    override suspend fun totalOf(holder: HolderId, itemKey: ItemKey): Long {
        val s = state.get()
        val key = s.keyOrNull(holder, itemKey) ?: return 0L
        return s.remainingAt[key] ?: 0L
    }

    @Reads
    override suspend fun totalsAt(holder: HolderId): Map<ItemKey, Long> {
        val s = state.get()
        val items = s.itemsAt[holder] ?: return emptyMap()
        if (items.isEmpty()) return emptyMap()
        val out = HashMap<ItemKey, Long>(items.size)
        for (itemKey in items) {
            val key = s.keyOrNull(holder, itemKey) ?: continue
            val total = s.remainingAt[key] ?: continue
            if (total != 0L) out[itemKey] = total
        }
        return if (out.isEmpty()) emptyMap() else out
    }

    @Reads
    override suspend fun placementOf(holder: HolderId, lotId: LotId): AccountLot? {
        return state.get().byLot[lotId]?.takeIf { it.holder == holder }
    }

    @Reads
    override suspend fun census(itemKey: ItemKey): Long {
        val s = state.get()
        val holders = s.holdersOf[itemKey] ?: return 0L
        var total = 0L
        for (holder in holders) {
            if (holder is HolderId.Source || holder is HolderId.Sink) continue
            val key = s.keyOrNull(holder, itemKey) ?: continue
            total += s.remainingAt[key] ?: 0L
        }
        return total
    }

    @Reads
    override suspend fun allPlacements(itemKey: ItemKey): List<AccountLot> {
        val s = state.get()
        return s.collectPlacements(s.holdersOf[itemKey]) { holder -> s.keyOrNull(holder, itemKey) }
    }

    @Reads
    override suspend fun placementsAt(holder: HolderId): List<AccountLot> {
        val s = state.get()
        return s.collectPlacements(s.itemsAt[holder]) { itemKey -> s.keyOrNull(holder, itemKey) }
    }

    @Reads
    override suspend fun currentHolderOf(lotId: LotId): HolderId? = state.get().holderOf[lotId]

    override suspend fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot {
        writer.checkIn()
        var s = state.get()
        s.byLot[lotId]?.let { error("lot $lotId is already placed at ${it.holder}") }
        val entry = AccountLot(holder, s.lots.getValue(lotId), quantity, Seq(nextSeq.getAndIncrement()))
        s = s.putPlacement(entry)
        s = s.copy(holderOf = s.holderOf.putting(lotId, holder))
        commit(s)
        return entry
    }

    override suspend fun remove(holder: HolderId, lotId: LotId) {
        writer.checkIn()
        val s = state.get().unplace(holder, lotId)
        commit(s.copy(holderOf = s.holderOf.removing(lotId)))
    }

    override suspend fun rehome(from: HolderId, to: HolderId, lotId: LotId): Quantity {
        writer.checkIn()
        var s = state.get()
        val entry = s.byLot[lotId]?.takeIf { it.holder == from }
            ?: error("lot $lotId is not currently placed at $from")
        if (from == to) return entry.remaining
        s = s.unplace(from, lotId)
        s = s.putPlacement(entry.copy(holder = to))
        commit(s.copy(holderOf = s.holderOf.putting(lotId, to)))
        return entry.remaining
    }

    override suspend fun relocate(from: HolderId, to: HolderId) {
        writer.checkIn()
        if (from == to) return
        var s = state.get()
        val items = s.itemsAt[from] ?: return
        if (items.isEmpty()) return
        val byLot = s.byLot.builder()
        val holderOf = s.holderOf.builder()
        s = s.copy(itemsAt = s.itemsAt.removing(from))
        for (itemKey in items) {
            val fromKey = s.keyOrNull(from, itemKey) ?: continue
            val moved = s.queues[fromKey] ?: continue
            val qty = s.remainingAt[fromKey] ?: 0L
            s = s.copy(
                queues = s.queues.removing(fromKey),
                remainingAt = s.remainingAt.removing(fromKey),
                holdersOf = s.dropFromSet(s.holdersOf, itemKey, from),
            )
            if (moved.isEmpty()) continue

            val interned = s.intern(to, itemKey)
            s = interned.first
            val toKey = interned.second
            val dest = FifoQueue.from(s.queues[toKey])
            dest.merge(FifoQueue.from(moved), { it.copy(holder = to) }) { rewritten ->
                byLot[rewritten.lot.id] = rewritten
                holderOf[rewritten.lot.id] = rewritten.holder
            }
            s = s.withQueue(toKey, dest).addRemaining(toKey, qty).indexAdd(to, itemKey)
        }
        commit(s.copy(byLot = byLot.build(), holderOf = holderOf.build()))
    }

    override suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity) {
        writer.checkIn()
        var s = state.get()
        val old = s.byLot[retiredLotId]?.takeIf { it.holder == holder }
            ?: error("no placement of $retiredLotId at $holder")
        val replacement = AccountLot(holder, s.lots.getValue(newLotId), remaining, old.fifoSeq)
        val key = s.requireKey(holder, old.lot.itemKey)
        val queue = FifoQueue.from(s.queues[key])
        queue.replaceLot(retiredLotId, replacement)
        s = s.withQueue(key, queue).copy(
            byLot = s.byLot.removing(retiredLotId).putting(newLotId, replacement),
            holderOf = s.holderOf.removing(retiredLotId).putting(newLotId, holder),
        )
        commit(s.addRemaining(key, remaining.raw - old.remaining.raw))
    }

    /**
     * Takes up to [stillNeeded] units off the oldest entry of [queue], adding what it took to [taken].
     *
     * A lot taken whole leaves the queue; a lot that holds more than is needed is split by [draft], and the part that
     * stays keeps its place. Returns how many units came off.
     */
    private fun takeFromHead(
        draft: Draft,
        holder: HolderId,
        queue: FifoQueue,
        entry: AccountLot,
        stillNeeded: Long,
        txn: TxnId,
        taken: ArrayList<LotPortion>,
    ): Long {
        val inThisLot = entry.remaining.raw
        if (inThisLot <= stillNeeded) {
            queue.pollFirst()
            taken.add(LotPortion(entry.lot.id, entry.remaining))
            draft.dropPlacement(entry.lot.id)
            draft.remainingDelta -= inThisLot
            return inThisLot
        }
        taken.add(draft.splitHead(holder, entry, stillNeeded, txn, queue))
        return stillNeeded
    }

    /**
     * The edits one consuming call makes to [RepositoryState], collected in builders and written out once.
     *
     * A withdrawal can touch many lots; building them separately would copy the persistent maps once per lot.
     */
    private inner class Draft(base: RepositoryState) {
        private val lots = base.lots.builder()
        private val byLot = base.byLot.builder()
        private val holderOf = base.holderOf.builder()
        private val edgesByParent = base.edgesByParent.builder()
        private val edgesByChild = base.edgesByChild.builder()
        var remainingDelta: Long = 0L

        /** Forgets where [lotId] sits, because the whole lot was taken. */
        fun dropPlacement(lotId: LotId) {
            byLot.remove(lotId)
            holderOf.remove(lotId)
        }

        /**
         * Splits the oldest entry into the [take] units that leave and the rest that stays at the same place in the
         * queue.
         *
         * Both parts are new lots, each linked to the original by a split edge.
         */
        fun splitHead(
            holder: HolderId,
            entry: AccountLot,
            take: Long,
            txn: TxnId,
            queue: FifoQueue,
        ): LotPortion {
            val parent = entry.lot.id
            val itemKey = entry.lot.itemKey
            val takenQty = Quantity(take)
            val keptQty = Quantity(entry.remaining.raw - take)
            val takenLot = Lot(LotId(nextLotId.getAndIncrement()), itemKey, takenQty, txn)
            val keptLot = Lot(LotId(nextLotId.getAndIncrement()), itemKey, keptQty, txn)
            val kept = AccountLot(holder, keptLot, keptQty, entry.fifoSeq)
            queue.replaceLot(parent, kept)
            lots[takenLot.id] = takenLot
            lots[keptLot.id] = keptLot
            byLot.remove(parent)
            byLot[keptLot.id] = kept
            holderOf.remove(parent)
            holderOf[keptLot.id] = holder
            putEdge(LotEdge.Split(takenLot.id, parent, takenQty))
            putEdge(LotEdge.Split(keptLot.id, parent, keptQty))
            remainingDelta -= take
            return LotPortion(takenLot.id, takenQty)
        }

        /**
         * The state after the edits: the builders written out, the account total moved by what was taken, and [queue]
         * stored.
         */
        fun commit(
            base: RepositoryState,
            holder: HolderId,
            itemKey: ItemKey,
            key: AccountKey,
            queue: FifoQueue
        ): RepositoryState {
            val remaining = (base.remainingAt[key] ?: 0L) + remainingDelta
            return base.copy(
                lots = lots.build(),
                byLot = byLot.build(),
                holderOf = holderOf.build(),
                edgesByParent = edgesByParent.build(),
                edgesByChild = edgesByChild.build(),
                remainingAt = if (remaining == 0L) base.remainingAt.removing(key) else base.remainingAt.putting(
                    key,
                    remaining
                ),
            ).withQueue(key, queue).dropIndexIfEmpty(holder, itemKey, key)
        }

        private fun putEdge(edge: LotEdge) {
            val byParent = (edgesByParent[edge.parent] ?: persistentHashMapOf()).putting(edge.child, edge)
            val byChild = (edgesByChild[edge.child] ?: persistentHashMapOf()).putting(edge.parent, edge)
            edgesByParent[edge.parent] = byParent
            edgesByChild[edge.child] = byChild
        }
    }

    /** The edges in [map] as a list nobody else can change. */
    private fun snapshotEdges(map: PersistentMap<LotId, LotEdge>?): List<LotEdge> {
        if (map.isNullOrEmpty()) return emptyList()
        return map.values.toList()
    }

}
