package com.tracel.engine.ledger

import com.tracel.annotations.Consume
import com.tracel.annotations.Fifo
import com.tracel.annotations.Index
import com.tracel.annotations.Intern
import com.tracel.annotations.Reads
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.Snapshot
import com.tracel.annotations.ThreadContext
import com.tracel.engine.ownership.SingleWriterGuard
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
import com.tracel.platform.storage.DirectUnitOfWork
import com.tracel.platform.storage.UnitOfWork
import kotlinx.atomicfu.atomic
import java.util.concurrent.atomic.AtomicReference
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.math.min

/** In-memory [LotRepository]. */
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryLotRepository : LotRepository, UnitOfWork by DirectUnitOfWork {
    private val writer = SingleWriterGuard()

    private val nextLotId = atomic(1L)
    private val nextSeq = atomic(1L)
    private val state = AtomicReference(State())

    override suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot {
        writer.checkIn()
        val lot = Lot(LotId(nextLotId.getAndIncrement()), itemKey, quantity, createdBy)
        val s = state.get()
        state.set(s.copy(lots = s.lots.put(lot.id, lot)))
        return lot
    }

    @Reads
    override suspend fun lot(id: LotId): Lot = state.get().lots.getValue(id)

    override suspend fun recordEdge(edge: LotEdge) {
        writer.checkIn()
        state.set(state.get().putEdge(edge))
    }

    override suspend fun removeEdge(parent: LotId, child: LotId) {
        writer.checkIn()
        state.set(state.get().removeEdge(parent, child))
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
        val queue = AccountQueue.from(key?.let { s.queues[it] })
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
        if (key != null) state.set(draft.commit(s, holder, itemKey, key, queue))
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
        val queue = AccountQueue.from(key?.let { s.queues[it] })
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
        state.set(draft.commit(s, holder, itemKey, key, queue))
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
        val s = state.get()
        s.lots.getValue(lotId)
        return s.byLot[lotId]?.takeIf { it.holder == holder }
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
        val entry = AccountLot(holder, s.lots.getValue(lotId), quantity, Seq(nextSeq.getAndIncrement()))
        s = s.putPlacement(entry)
        s = s.copy(holderOf = s.holderOf.put(lotId, holder))
        state.set(s)
        return entry
    }

    override suspend fun remove(holder: HolderId, lotId: LotId) {
        writer.checkIn()
        val s = state.get().unplace(holder, lotId)
        state.set(s.copy(holderOf = s.holderOf.remove(lotId)))
    }

    override suspend fun rehome(from: HolderId, to: HolderId, lotId: LotId) {
        writer.checkIn()
        if (from == to) return
        var s = state.get()
        val entry = s.byLot[lotId]?.takeIf { it.holder == from }
            ?: error("lot $lotId is not currently placed at $from")
        s = s.unplace(from, lotId)
        s = s.putPlacement(entry.copy(holder = to))
        state.set(s.copy(holderOf = s.holderOf.put(lotId, to)))
    }

    override suspend fun relocate(from: HolderId, to: HolderId) {
        writer.checkIn()
        if (from == to) return
        var s = state.get()
        val items = s.itemsAt[from] ?: return
        if (items.isEmpty()) return
        val byLot = s.byLot.builder()
        val holderOf = s.holderOf.builder()
        s = s.copy(itemsAt = s.itemsAt.remove(from))
        for (itemKey in items) {
            val fromKey = s.keyOrNull(from, itemKey) ?: continue
            val moved = s.queues[fromKey] ?: continue
            val qty = s.remainingAt[fromKey] ?: 0L
            s = s.copy(
                queues = s.queues.remove(fromKey),
                remainingAt = s.remainingAt.remove(fromKey),
                holdersOf = s.dropFromSet(s.holdersOf, itemKey, from),
            )
            if (moved.isEmpty()) continue

            val interned = s.intern(to, itemKey)
            s = interned.first
            val toKey = interned.second
            val dest = AccountQueue.from(s.queues[toKey])
            dest.merge(AccountQueue.from(moved), { it.copy(holder = to) }) { rewritten ->
                byLot[rewritten.lot.id] = rewritten
                holderOf[rewritten.lot.id] = rewritten.holder
            }
            s = s.withQueue(toKey, dest).addRemaining(toKey, qty).indexAdd(to, itemKey)
        }
        state.set(s.copy(byLot = byLot.build(), holderOf = holderOf.build()))
    }

    override suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity) {
        writer.checkIn()
        var s = state.get()
        val old = s.byLot[retiredLotId]?.takeIf { it.holder == holder }
            ?: error("no placement of $retiredLotId at $holder")
        val replacement = AccountLot(holder, s.lots.getValue(newLotId), remaining, old.fifoSeq)
        val key = s.requireKey(holder, old.lot.itemKey)
        val queue = AccountQueue.from(s.queues[key])
        queue.replaceLot(retiredLotId, replacement)
        s = s.withQueue(key, queue).copy(
            byLot = s.byLot.remove(retiredLotId).put(newLotId, replacement),
            holderOf = s.holderOf.remove(retiredLotId).put(newLotId, holder),
        )
        state.set(s.addRemaining(key, remaining.raw - old.remaining.raw))
    }

    private fun takeFromHead(
        draft: Draft,
        holder: HolderId,
        queue: AccountQueue,
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

    private inner class Draft(base: State) {
        private val lots = base.lots.builder()
        private val byLot = base.byLot.builder()
        private val holderOf = base.holderOf.builder()
        private val edgesByParent = base.edgesByParent.builder()
        private val edgesByChild = base.edgesByChild.builder()
        var remainingDelta: Long = 0L

        fun dropPlacement(lotId: LotId) {
            byLot.remove(lotId)
            holderOf.remove(lotId)
        }

        fun splitHead(
            holder: HolderId,
            entry: AccountLot,
            take: Long,
            txn: TxnId,
            queue: AccountQueue,
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

        fun commit(base: State, holder: HolderId, itemKey: ItemKey, key: AccountKey, queue: AccountQueue): State {
            val remaining = (base.remainingAt[key] ?: 0L) + remainingDelta
            return base.copy(
                lots = lots.build(),
                byLot = byLot.build(),
                holderOf = holderOf.build(),
                edgesByParent = edgesByParent.build(),
                edgesByChild = edgesByChild.build(),
                remainingAt = if (remaining == 0L) base.remainingAt.remove(key) else base.remainingAt.put(key, remaining),
            ).withQueue(key, queue).dropIndexIfEmpty(holder, itemKey, key)
        }

        private fun putEdge(edge: LotEdge) {
            val byParent = (edgesByParent[edge.parent] ?: persistentHashMapOf()).put(edge.child, edge)
            val byChild = (edgesByChild[edge.child] ?: persistentHashMapOf()).put(edge.parent, edge)
            edgesByParent[edge.parent] = byParent
            edgesByChild[edge.child] = byChild
        }
    }

    private fun snapshotEdges(map: PersistentMap<LotId, LotEdge>?): List<LotEdge> {
        if (map.isNullOrEmpty()) return emptyList()
        return map.values.toList()
    }

    @Snapshot
    private data class State(
        val lots: PersistentMap<LotId, Lot> = persistentHashMapOf(),
        val edgesByParent: PersistentMap<LotId, PersistentMap<LotId, LotEdge>> = persistentHashMapOf(),
        val edgesByChild: PersistentMap<LotId, PersistentMap<LotId, LotEdge>> = persistentHashMapOf(),
        @Intern val internedHolders: PersistentMap<HolderId, Int> = persistentHashMapOf(),
        @Intern val internedItems: PersistentMap<ItemKey, Int> = persistentHashMapOf(),
        val nextHolderNo: Int = 1,
        val nextItemNo: Int = 1,
        @Fifo(orderBy = "fifoSeq") val queues: PersistentMap<AccountKey, PersistentList<AccountLot>> = persistentHashMapOf(),
        val byLot: PersistentMap<LotId, AccountLot> = persistentHashMapOf(),
        val holderOf: PersistentMap<LotId, HolderId> = persistentHashMapOf(),
        val remainingAt: PersistentMap<AccountKey, Long> = persistentHashMapOf(),
        @Index val itemsAt: PersistentMap<HolderId, PersistentSet<ItemKey>> = persistentHashMapOf(),
        @Index val holdersOf: PersistentMap<ItemKey, PersistentSet<HolderId>> = persistentHashMapOf(),
    ) {
        fun keyOrNull(holder: HolderId, itemKey: ItemKey): AccountKey? {
            val h = internedHolders[holder] ?: return null
            val i = internedItems[itemKey] ?: return null
            return AccountKey.pack(h, i)
        }

        fun requireKey(holder: HolderId, itemKey: ItemKey): AccountKey =
            keyOrNull(holder, itemKey) ?: error("no interned account for $holder / $itemKey")

        fun intern(holder: HolderId, itemKey: ItemKey): Pair<State, AccountKey> {
            var s = this
            val h = internedHolders[holder] ?: run {
                val n = s.nextHolderNo
                s = s.copy(internedHolders = s.internedHolders.put(holder, n), nextHolderNo = n + 1)
                n
            }
            val i = s.internedItems[itemKey] ?: run {
                val n = s.nextItemNo
                s = s.copy(internedItems = s.internedItems.put(itemKey, n), nextItemNo = n + 1)
                n
            }
            return s to AccountKey.pack(h, i)
        }

        fun putEdge(edge: LotEdge): State {
            val byParent = (edgesByParent[edge.parent] ?: persistentHashMapOf()).put(edge.child, edge)
            val byChild = (edgesByChild[edge.child] ?: persistentHashMapOf()).put(edge.parent, edge)
            return copy(
                edgesByParent = edgesByParent.put(edge.parent, byParent),
                edgesByChild = edgesByChild.put(edge.child, byChild),
            )
        }

        fun removeEdge(parent: LotId, child: LotId): State = copy(
            edgesByParent = dropNested(edgesByParent, parent, child),
            edgesByChild = dropNested(edgesByChild, child, parent),
        )

        fun putPlacement(entry: AccountLot): State {
            val interned = intern(entry.holder, entry.lot.itemKey)
            val s = interned.first
            val key = interned.second
            val queue = AccountQueue.from(s.queues[key])
            queue.put(entry)
            return s.withQueue(key, queue)
                .copy(byLot = s.byLot.put(entry.lot.id, entry))
                .addRemaining(key, entry.remaining.raw)
                .indexAdd(entry.holder, entry.lot.itemKey)
        }

        fun unplace(holder: HolderId, lotId: LotId): State {
            val entry = byLot[lotId]?.takeIf { it.holder == holder } ?: return this
            val key = keyOrNull(holder, entry.lot.itemKey) ?: return this
            val queue = AccountQueue.from(queues[key])
            if (queue.remove(lotId) == null) return this
            return withQueue(key, queue)
                .copy(byLot = byLot.remove(lotId))
                .addRemaining(key, -entry.remaining.raw)
                .dropIndexIfEmpty(holder, entry.lot.itemKey, key)
        }

        fun withQueue(key: AccountKey, queue: AccountQueue): State =
            copy(queues = if (queue.isEmpty) queues.remove(key) else queues.put(key, queue.toPersistentList()))

        fun addRemaining(key: AccountKey, delta: Long): State {
            if (delta == 0L) return this
            val next = (remainingAt[key] ?: 0L) + delta
            return copy(remainingAt = if (next == 0L) remainingAt.remove(key) else remainingAt.put(key, next))
        }

        fun indexAdd(holder: HolderId, itemKey: ItemKey): State {
            val items = (itemsAt[holder] ?: persistentSetOf()).add(itemKey)
            val holders = (holdersOf[itemKey] ?: persistentSetOf()).add(holder)
            return copy(itemsAt = itemsAt.put(holder, items), holdersOf = holdersOf.put(itemKey, holders))
        }

        fun dropIndexIfEmpty(holder: HolderId, itemKey: ItemKey, key: AccountKey): State {
            if (queues.containsKey(key)) return this
            return copy(
                itemsAt = dropFromSet(itemsAt, holder, itemKey),
                holdersOf = dropFromSet(holdersOf, itemKey, holder),
            )
        }

        fun <T> collectPlacements(keys: PersistentSet<T>?, account: (T) -> AccountKey?): List<AccountLot> {
            if (keys.isNullOrEmpty()) return emptyList()
            var out: ArrayList<AccountLot>? = null
            for (key in keys) {
                val accountKey = account(key) ?: continue
                val queue = queues[accountKey] ?: continue
                if (queue.isEmpty()) continue
                val dest = out ?: ArrayList<AccountLot>(queue.size).also { out = it }
                dest.addAll(queue)
            }
            return out ?: emptyList()
        }

        private fun <K, I, V> dropNested(
            map: PersistentMap<K, PersistentMap<I, V>>,
            key: K,
            inner: I,
        ): PersistentMap<K, PersistentMap<I, V>> {
            val nested = map[key] ?: return map
            val next = nested.remove(inner)
            return if (next.isEmpty()) map.remove(key) else map.put(key, next)
        }

        fun <K, E> dropFromSet(
            map: PersistentMap<K, PersistentSet<E>>,
            key: K,
            element: E,
        ): PersistentMap<K, PersistentSet<E>> {
            val set = map[key] ?: return map
            val next = set.remove(element)
            return if (next.isEmpty()) map.remove(key) else map.put(key, next)
        }
    }

    /**
     * Writer-local FIFO. Published as a [PersistentList] so readers never walk a list
     * being unlinked. [place] always appends a new max seq; [relocate] merges by seq.
     */
    private class AccountQueue {
        private class Node(var entry: AccountLot) {
            var prev: Node? = null
            var next: Node? = null
        }

        private val byLot = HashMap<LotId, Node>()
        private var head: Node? = null
        private var tail: Node? = null

        val isEmpty: Boolean get() = head == null

        fun peekFirst(): AccountLot? = head?.entry

        fun pollFirst(): AccountLot? {
            val node = head ?: return null
            unlink(node)
            return node.entry
        }

        fun put(entry: AccountLot) {
            val existing = byLot[entry.lot.id]
            if (existing != null) {
                existing.entry = entry
                return
            }
            val node = Node(entry)
            byLot[entry.lot.id] = node
            insertBySeq(node)
        }

        fun replaceLot(retired: LotId, replacement: AccountLot) {
            val node = byLot.remove(retired) ?: return
            node.entry = replacement
            byLot[replacement.lot.id] = node
        }

        fun remove(lotId: LotId): AccountLot? {
            val node = byLot[lotId] ?: return null
            unlink(node)
            return node.entry
        }

        fun merge(
            other: AccountQueue,
            rewrite: (AccountLot) -> AccountLot,
            onMoved: (AccountLot) -> Unit,
        ) {
            var cur = other.head
            while (cur != null) {
                val next = cur.next
                other.unlink(cur)
                val rewritten = rewrite(cur.entry)
                cur.entry = rewritten
                byLot[rewritten.lot.id] = cur
                insertBySeq(cur)
                onMoved(rewritten)
                cur = next
            }
        }

        // TODO: refactor
        fun toPersistentList(): PersistentList<AccountLot> {
            val builder = persistentListOf<AccountLot>().builder()
            var cur = head
            while (cur != null) {
                builder.add(cur.entry)
                cur = cur.next
            }
            return builder.build()
        }

        private fun insertBySeq(node: Node) {
            val seq = node.entry.fifoSeq.raw
            val last = tail
            if (last == null) {
                head = node
                tail = node
                return
            }
            if (seq >= last.entry.fifoSeq.raw) {
                last.next = node
                node.prev = last
                tail = node
                return
            }
            val first = head ?: return
            if (seq <= first.entry.fifoSeq.raw) {
                node.next = first
                first.prev = node
                head = node
                return
            }
            var cur = last.prev
            while (cur != null && cur.entry.fifoSeq.raw > seq) cur = cur.prev
            val after = cur ?: return
            val next = after.next
            node.prev = after
            node.next = next
            after.next = node
            next?.prev = node
        }

        private fun unlink(node: Node) {
            val prev = node.prev
            val next = node.next
            if (prev != null) prev.next = next else head = next
            if (next != null) next.prev = prev else tail = prev
            node.prev = null
            node.next = null
            byLot.remove(node.entry.lot.id)
        }

        companion object {
            fun from(list: PersistentList<AccountLot>?): AccountQueue {
                val queue = AccountQueue()
                if (list != null) for (entry in list) queue.put(entry)
                return queue
            }
        }
    }

    @JvmInline
    private value class AccountKey(val raw: Long) {
        companion object {
            fun pack(holder: Int, item: Int): AccountKey =
                AccountKey((holder.toLong() shl 32) or (item.toLong() and 0xFFFF_FFFFL))
        }
    }
}
