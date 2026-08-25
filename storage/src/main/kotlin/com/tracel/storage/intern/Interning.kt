package com.tracel.storage.intern

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Packed
import com.tracel.storage.codec.Records
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns holders, item keys and worlds into 4-byte ids, and does it fast enough to sit on a
 * region thread.
 *
 * Two paths, because two callers with nothing in common:
 *
 * - Capture ([holderIdForCapture], [itemKeyIdForCapture]) runs on a Minecraft server region thread
 * and may not touch the store, block, or allocate in the steady state. It is a bounded-cache lookup
 * and nothing else. A miss — a chest nobody has touched since the cache last evicted — hands
 * back a provisional negative ID and files the value for the storage thread to canonicalize.
 * - Storage ([internHolder], [resolveHolder], etc.) runs on the storage thread inside an open
 * unit of work, is allowed to read and write, and is the only thing that ever assigns a real ID.
 *
 * IDs start at 1. Zero is "nobody", which is what makes a transaction with no cause and a
 * transaction caused by holder zero different records.
 */
class Interning(private val cacheSize: Long = DEFAULT_CACHE_SIZE) {
    private val holderIds: Cache<HolderId, Int> = Caffeine.newBuilder().maximumSize(cacheSize).build()
    private val holdersById: Cache<Int, HolderId> = Caffeine.newBuilder().maximumSize(cacheSize).build()
    private val itemKeyIds: Cache<ItemKey, Int> = Caffeine.newBuilder().maximumSize(ITEM_KEY_CACHE).build()
    private val itemKeysById: Cache<Int, ItemKey> = Caffeine.newBuilder().maximumSize(ITEM_KEY_CACHE).build()
    private val worldIds: Cache<WorldId, Int> = Caffeine.newBuilder().maximumSize(WORLD_CACHE).build()
    private val worldsById: Cache<Int, WorldId> = Caffeine.newBuilder().maximumSize(WORLD_CACHE).build()

    private val nextHolderId = AtomicInteger(0)
    private val nextItemKeyId = AtomicInteger(0)
    private val nextWorldId = AtomicInteger(0)

    /** Values a region thread named before the storage thread had a real id for them. */
    private val provisionalIds = ConcurrentHashMap<Any, Int>()
    private val provisionalValues = ConcurrentHashMap<Int, Any>()
    private val provisionalToReal = ConcurrentHashMap<Int, Int>()
    private val nextProvisional = AtomicInteger(0)

    /** Events refused because the provisional table was full. Same category of loss as a full ring. */
    val droppedForCapacity: Long get() = drops.get()

    private val drops = AtomicLong(0)

    // Сapture path: region threads

    /**
     * A direct-mapped cache per thread, in front of the shared one.
     *
     * A region thread sees the same handful of holders over and over... One hopper, its chest,
     * one item key — and a shared bounded cache still costs a hash, a table probe and an
     * eviction-policy record for every one of them. This is an array index and an `equals`.
     *
     * Correctness comes from [generation]: a purge bumps it, and every hit checks it, so a
     * stale per-thread entry can never outlive the mapping it names.
     */
    private class ThreadCache {
        val keys = arrayOfNulls<Any>(SLOTS)
        val ids = IntArray(SLOTS)
        var generation = -1L

        fun lookup(value: Any, generation: Long): Int {
            if (this.generation != generation) return 0
            val slot = slotOf(value)
            return if (keys[slot] == value) ids[slot] else 0
        }

        fun store(value: Any, id: Int, generation: Long) {
            if (this.generation != generation) {
                java.util.Arrays.fill(keys, null)
                this.generation = generation
            }
            val slot = slotOf(value)
            keys[slot] = value
            ids[slot] = id
        }

        private fun slotOf(value: Any): Int {
            val h = value.hashCode()
            return (h xor (h ushr 16)) and (SLOTS - 1)
        }

        companion object {
            /// Small enough to stay in L1
            const val SLOTS = 256
        }
    }

    private val local = ThreadLocal.withInitial { ThreadCache() }

    @Volatile
    private var generation = 0L

    /** A real id, a provisional one, or 0 meaning "drop this event". Never blocks, never reads the store. */
    fun holderIdForCapture(holder: HolderId): Int {
        val at = generation
        val cache = local.get()
        val cached = cache.lookup(holder, at)
        if (cached != 0) return cached
        val id = holderIds.getIfPresent(holder) ?: return provisional(holder)
        cache.store(holder, id, at)
        return id
    }

    fun itemKeyIdForCapture(itemKey: ItemKey): Int {
        val at = generation
        val cache = local.get()
        val cached = cache.lookup(itemKey, at)
        if (cached != 0) return cached
        val id = itemKeyIds.getIfPresent(itemKey) ?: return provisional(itemKey)
        cache.store(itemKey, id, at)
        return id
    }

    private fun provisional(value: Any): Int {
        if (provisionalValues.size >= PROVISIONAL_LIMIT) {
            drops.incrementAndGet()
            return 0
        }
        return provisionalIds.computeIfAbsent(value) { fresh ->
            val id = -nextProvisional.incrementAndGet()
            provisionalValues[id] = fresh
            id
        }
    }

    // Storage path: the writer thread, inside an open unit

    /** Resolves whatever a ring slot carried. */
    fun canonical(unit: StorageUnit, id: Int): Int {
        if (id >= 0) return id
        provisionalToReal[id]?.let { return it }
        val value = provisionalValues[id] ?: return 0
        val real = when (value) {
            is HolderId -> internHolder(unit, value)
            is ItemKey -> internItemKey(unit, value)
            else -> error("nothing else is ever interned provisionally: ${value::class}")
        }
        provisionalToReal[id] = real
        return real
    }

    /**
     * Forgets provisional bookkeeping once every event that could name one has been drained.
     * Callers must be sure the ring is empty; the drainer is.
     */
    fun compactProvisional() {
        if (provisionalValues.size < PROVISIONAL_COMPACT_AT) return
        provisionalIds.clear()
        provisionalValues.clear()
        provisionalToReal.clear()
    }

    fun internHolder(unit: StorageUnit, holder: HolderId): Int =
        intern(unit, Keys.NS_HOLDER, holder, holderIds, holdersById, nextHolderId, Packed::holder)

    fun internItemKey(unit: StorageUnit, itemKey: ItemKey): Int =
        intern(unit, Keys.NS_ITEM_KEY, itemKey, itemKeyIds, itemKeysById, nextItemKeyId, Packed::itemKey)

    fun internWorld(unit: StorageUnit, world: WorldId): Int =
        intern(unit, Keys.NS_WORLD, world, worldIds, worldsById, nextWorldId, Packed::world)

    /** The ID [holder] already has, or `null` — a filter that matches nothing can stop right here. */
    fun findHolderId(unit: StorageUnit, holder: HolderId): Int? =
        holderIds.getIfPresent(holder) ?: lookup(unit, Keys.NS_HOLDER, Packed.holder(holder))
            ?.also { holderIds.put(holder, it); holdersById.put(it, holder) }

    fun findItemKeyId(unit: StorageUnit, itemKey: ItemKey): Int? =
        itemKeyIds.getIfPresent(itemKey) ?: lookup(unit, Keys.NS_ITEM_KEY, Packed.itemKey(itemKey))
            ?.also { itemKeyIds.put(itemKey, it); itemKeysById.put(it, itemKey) }

    fun resolveHolder(unit: StorageUnit, id: Int): HolderId =
        holdersById.getIfPresent(id) ?: Packed.decodeHolder(
            unit.get(Keys.internForward(Keys.NS_HOLDER, id)) ?: error("holder id $id was never interned")
        ).also { holdersById.put(id, it); holderIds.put(it, id) }

    fun resolveItemKey(unit: StorageUnit, id: Int): ItemKey =
        itemKeysById.getIfPresent(id) ?: Packed.decodeItemKey(
            unit.get(Keys.internForward(Keys.NS_ITEM_KEY, id)) ?: error("item key id $id was never interned")
        ).also { itemKeysById.put(id, it); itemKeyIds.put(it, id) }

    /** Reloads the id counters from the store. Called once, when the database is opened. */
    fun restore(unit: StorageUnit) {
        nextHolderId.set(counter(unit, Keys.NS_HOLDER))
        nextItemKeyId.set(counter(unit, Keys.NS_ITEM_KEY))
        nextWorldId.set(counter(unit, Keys.NS_WORLD))
    }

    /** Drops every cached mapping. For a purge, which deletes the records these describe. */
    fun forget() {
        generation++
        listOf(holderIds, holdersById, itemKeyIds, itemKeysById, worldIds, worldsById).forEach { it.invalidateAll() }
        provisionalIds.clear()
        provisionalValues.clear()
        provisionalToReal.clear()
        nextHolderId.set(0)
        nextItemKeyId.set(0)
        nextWorldId.set(0)
    }

    private fun <T : Any> intern(
        unit: StorageUnit,
        namespace: Byte,
        value: T,
        byValue: Cache<T, Int>,
        byId: Cache<Int, T>,
        counter: AtomicInteger,
        pack: (T) -> ByteArray,
    ): Int {
        byValue.getIfPresent(value)?.let { return it }
        val packed = pack(value)
        lookup(unit, namespace, packed)?.let {
            byValue.put(value, it)
            byId.put(it, value)
            return it
        }

        val id = counter.incrementAndGet()
        unit.putPinned(Keys.internForward(namespace, id), packed)
        unit.putPinned(Keys.internReverse(namespace, packed), Records.int(id))
        unit.putPinned(Keys.counter(COUNTER_BASE + namespace), Records.long(id.toLong()))
        byValue.put(value, id)
        byId.put(id, value)
        return id
    }

    private fun lookup(unit: StorageUnit, namespace: Byte, packed: ByteArray): Int? =
        unit.get(Keys.internReverse(namespace, packed))?.let(Records::asInt)

    private fun counter(unit: StorageUnit, namespace: Byte): Int =
        unit.get(Keys.counter(COUNTER_BASE + namespace))?.let(Records::asLong)?.toInt() ?: 0

    private companion object {
        /** ~24 MiB of holders at steady state. */
        const val DEFAULT_CACHE_SIZE = 262_144L
        const val ITEM_KEY_CACHE = 16_384L
        const val WORLD_CACHE = 256L

        /** How many unseen values may be in flight to the storage thread before captures start dropping. */
        const val PROVISIONAL_LIMIT = 65_536
        const val PROVISIONAL_COMPACT_AT = 4_096

        /** Intern counters live in the counter family, above anything the ledger names. */
        const val COUNTER_BASE = 0x7000_0000
    }
}
