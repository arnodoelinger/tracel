package com.tracel.engine.ledger.repository.memory

import com.tracel.model.holder.HolderId
import com.tracel.model.id.*
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import kotlinx.collections.immutable.*

/**
 * One immutable picture of everything the in-memory repository knows.
 *
 * A reader takes the picture it finds and never sees a half-finished write: the single writer builds the next one with
 * [copy] and publishes it whole. Every map is persistent, so a new picture shares almost all of its parent.
 */
internal data class RepositoryState(
    val lots: PersistentMap<LotId, Lot> = persistentHashMapOf(),
    val edgesByParent: PersistentMap<LotId, PersistentMap<LotId, LotEdge>> = persistentHashMapOf(),
    val edgesByChild: PersistentMap<LotId, PersistentMap<LotId, LotEdge>> = persistentHashMapOf(),
    val internedHolders: PersistentMap<HolderId, Int> = persistentHashMapOf(),
    val internedItems: PersistentMap<ItemKey, Int> = persistentHashMapOf(),
    val nextHolderNo: Int = 1,
    val nextItemNo: Int = 1,
    val queues: PersistentMap<AccountKey, PersistentList<AccountLot>> = persistentHashMapOf(),
    val byLot: PersistentMap<LotId, AccountLot> = persistentHashMapOf(),
    val holderOf: PersistentMap<LotId, HolderId> = persistentHashMapOf(),
    val remainingAt: PersistentMap<AccountKey, Long> = persistentHashMapOf(),
    val itemsAt: PersistentMap<HolderId, PersistentSet<ItemKey>> = persistentHashMapOf(),
    val holdersOf: PersistentMap<ItemKey, PersistentSet<HolderId>> = persistentHashMapOf(),
) {
    /**
     * The account of [holder] and [itemKey], or `null` if either was never interned and so nothing was ever placed
     * there.
     */
    fun keyOrNull(holder: HolderId, itemKey: ItemKey): AccountKey? {
        val h = internedHolders[holder] ?: return null
        val i = internedItems[itemKey] ?: return null
        return AccountKey.pack(h, i)
    }

    /** Same as [keyOrNull], for callers that know the account exists. */
    fun requireKey(holder: HolderId, itemKey: ItemKey): AccountKey =
        keyOrNull(holder, itemKey) ?: error("no interned account for $holder / $itemKey")

    /** Gives [holder] and [itemKey] a number each if they have none, and returns the new picture with their account. */
    fun intern(holder: HolderId, itemKey: ItemKey): Pair<RepositoryState, AccountKey> {
        var s = this
        val h = internedHolders[holder] ?: run {
            val n = s.nextHolderNo
            s = s.copy(internedHolders = s.internedHolders.putting(holder, n), nextHolderNo = n + 1)
            n
        }
        val i = s.internedItems[itemKey] ?: run {
            val n = s.nextItemNo
            s = s.copy(internedItems = s.internedItems.putting(itemKey, n), nextItemNo = n + 1)
            n
        }
        return s to AccountKey.pack(h, i)
    }

    /** Records [edge] in both directions of the lot graph. */
    fun putEdge(edge: LotEdge): RepositoryState {
        val byParent = (edgesByParent[edge.parent] ?: persistentHashMapOf()).putting(edge.child, edge)
        val byChild = (edgesByChild[edge.child] ?: persistentHashMapOf()).putting(edge.parent, edge)
        return copy(
            edgesByParent = edgesByParent.putting(edge.parent, byParent),
            edgesByChild = edgesByChild.putting(edge.child, byChild),
        )
    }

    /** Forgets the edge from [parent] to [child] in both directions, dropping a lot's map once it has no edges left. */
    fun removeEdge(parent: LotId, child: LotId): RepositoryState = copy(
        edgesByParent = dropNested(edgesByParent, parent, child),
        edgesByChild = dropNested(edgesByChild, child, parent),
    )

    /**
     * Puts [entry] in its account's queue at the place its sequence number gives, and keeps [byLot], the account total
     * and the indexes in step.
     */
    fun putPlacement(entry: AccountLot): RepositoryState {
        val interned = intern(entry.holder, entry.lot.itemKey)
        val s = interned.first
        val key = interned.second
        val queue = FifoQueue.from(s.queues[key])
        queue.put(entry)
        return s.withQueue(key, queue)
            .copy(byLot = s.byLot.putting(entry.lot.id, entry))
            .addRemaining(key, entry.remaining.raw)
            .indexAdd(entry.holder, entry.lot.itemKey)
    }

    /**
     * Takes [lotId] out of [holder]'s queue and everything derived from it; unchanged if the lot is not placed there.
     */
    fun unplace(holder: HolderId, lotId: LotId): RepositoryState {
        val entry = byLot[lotId]?.takeIf { it.holder == holder } ?: return this
        val key = keyOrNull(holder, entry.lot.itemKey) ?: return this
        val queue = FifoQueue.from(queues[key])
        if (queue.remove(lotId) == null) return this
        return withQueue(key, queue)
            .copy(byLot = byLot.removing(lotId))
            .addRemaining(key, -entry.remaining.raw)
            .dropIndexIfEmpty(holder, entry.lot.itemKey, key)
    }

    /** Stores [queue] under [key], or forgets [key] when the queue is empty. */
    fun withQueue(key: AccountKey, queue: FifoQueue): RepositoryState =
        copy(queues = if (queue.isEmpty) queues.removing(key) else queues.putting(key, queue.toPersistentList()))

    /** Moves the total of [key] by [delta], forgetting it when it reaches zero. */
    fun addRemaining(key: AccountKey, delta: Long): RepositoryState {
        if (delta == 0L) return this
        val next = (remainingAt[key] ?: 0L) + delta
        return copy(remainingAt = if (next == 0L) remainingAt.removing(key) else remainingAt.putting(key, next))
    }

    /** Records that [holder] has [itemKey] in both indexes. */
    fun indexAdd(holder: HolderId, itemKey: ItemKey): RepositoryState {
        val items = (itemsAt[holder] ?: persistentSetOf()).adding(itemKey)
        val holders = (holdersOf[itemKey] ?: persistentSetOf()).adding(holder)
        return copy(itemsAt = itemsAt.putting(holder, items), holdersOf = holdersOf.putting(itemKey, holders))
    }

    /** Drops the indexes of an account, but only once its queue is gone. */
    fun dropIndexIfEmpty(holder: HolderId, itemKey: ItemKey, key: AccountKey): RepositoryState {
        if (queues.containsKey(key)) return this
        return copy(
            itemsAt = dropFromSet(itemsAt, holder, itemKey),
            holdersOf = dropFromSet(holdersOf, itemKey, holder),
        )
    }

    /**
     * Every entry of every queue that [keys] name, each key turned into an account by [account]; empty when there are
     * none.
     */
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

    /** Removes [inner] from the map under [key], and [key] too when that leaves it empty. */
    private fun <K, I, V> dropNested(
        map: PersistentMap<K, PersistentMap<I, V>>,
        key: K,
        inner: I,
    ): PersistentMap<K, PersistentMap<I, V>> {
        val nested = map[key] ?: return map
        val next = nested.removing(inner)
        return if (next.isEmpty()) map.removing(key) else map.putting(key, next)
    }

    /** Removes [element] from the set under [key], and [key] too when that leaves it empty. */
    fun <K, E> dropFromSet(
        map: PersistentMap<K, PersistentSet<E>>,
        key: K,
        element: E,
    ): PersistentMap<K, PersistentSet<E>> {
        val set = map[key] ?: return map
        val next = set.removing(element)
        return if (next.isEmpty()) map.removing(key) else map.putting(key, next)
    }
}
