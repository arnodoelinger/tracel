package com.tracel.storage.intern

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockDataKey
import com.tracel.model.world.EntityTypeKey
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Packed
import com.tracel.storage.codec.Records
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns holders, item keys, worlds, block states and entity types into 4-byte ids, and does it
 * fast enough to sit on a region thread.
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
class Interning(cacheSize: Long = DEFAULT_CACHE_SIZE) {
    private val holderIds: Cache<HolderId, Int> = bounded(cacheSize)
    private val holdersById: Cache<Int, HolderId> = bounded(cacheSize)
    private val itemKeyIds: Cache<ItemKey, Int> = bounded(ITEM_KEY_CACHE)
    private val itemKeysById: Cache<Int, ItemKey> = bounded(ITEM_KEY_CACHE)
    private val worldIds: Cache<WorldId, Int> = bounded(WORLD_CACHE)
    private val worldsById: Cache<Int, WorldId> = bounded(WORLD_CACHE)
    private val blockDataIds: Cache<BlockDataKey, Int> = bounded(BLOCK_DATA_CACHE)
    private val blockDataById: Cache<Int, BlockDataKey> = bounded(BLOCK_DATA_CACHE)
    private val entityTypeIds: Cache<EntityTypeKey, Int> = bounded(ENTITY_TYPE_CACHE)
    private val entityTypesById: Cache<Int, EntityTypeKey> = bounded(ENTITY_TYPE_CACHE)

    private val nextHolderId = AtomicInteger(0)
    private val nextItemKeyId = AtomicInteger(0)
    private val nextWorldId = AtomicInteger(0)
    private val nextBlockDataId = AtomicInteger(0)
    private val nextEntityTypeId = AtomicInteger(0)

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

    fun blockDataIdForCapture(blockData: BlockDataKey): Int {
        val at = generation
        val cache = local.get()
        val cached = cache.lookup(blockData, at)
        if (cached != 0) return cached
        val id = blockDataIds.getIfPresent(blockData) ?: return provisional(blockData)
        cache.store(blockData, id, at)
        return id
    }

    fun worldIdForCapture(world: WorldId): Int {
        val at = generation
        val cache = local.get()
        val cached = cache.lookup(world, at)
        if (cached != 0) return cached
        val id = worldIds.getIfPresent(world) ?: return provisional(world)
        cache.store(world, id, at)
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

    fun canonical(unit: StorageUnit, id: Int): Int {
        if (id >= 0) return id
        provisionalToReal[id]?.let { return it }
        val value = provisionalValues[id] ?: return 0
        val real = when (value) {
            is HolderId -> internHolder(unit, value)
            is ItemKey -> internItemKey(unit, value)
            is WorldId -> internWorld(unit, value)
            is BlockDataKey -> internBlockData(unit, value)
            is EntityTypeKey -> internEntityType(unit, value)
            else -> error("nothing else is ever interned provisionally: ${value::class}")
        }
        provisionalToReal[id] = real
        return real
    }

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

    fun internBlockData(unit: StorageUnit, blockData: BlockDataKey): Int =
        intern(unit, Keys.NS_BLOCK_DATA, blockData, blockDataIds, blockDataById, nextBlockDataId, Packed::blockData)

    fun internEntityType(unit: StorageUnit, entityType: EntityTypeKey): Int =
        intern(unit, Keys.NS_ENTITY_TYPE, entityType, entityTypeIds, entityTypesById, nextEntityTypeId, Packed::entityType)

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

    fun findWorldId(unit: StorageUnit, world: WorldId): Int? =
        worldIds.getIfPresent(world) ?: lookup(unit, Keys.NS_WORLD, Packed.world(world))
            ?.also { worldIds.put(world, it); worldsById.put(it, world) }

    fun resolveWorld(unit: StorageUnit, id: Int): WorldId =
        worldsById.getIfPresent(id) ?: Packed.decodeWorld(
            unit.get(Keys.internForward(Keys.NS_WORLD, id)) ?: error("world id $id was never interned")
        ).also { worldsById.put(id, it); worldIds.put(it, id) }

    fun resolveBlockData(unit: StorageUnit, id: Int): BlockDataKey =
        blockDataById.getIfPresent(id) ?: Packed.decodeBlockData(
            unit.get(Keys.internForward(Keys.NS_BLOCK_DATA, id)) ?: error("block data id $id was never interned")
        ).also { blockDataById.put(id, it); blockDataIds.put(it, id) }

    fun resolveEntityType(unit: StorageUnit, id: Int): EntityTypeKey =
        entityTypesById.getIfPresent(id) ?: Packed.decodeEntityType(
            unit.get(Keys.internForward(Keys.NS_ENTITY_TYPE, id)) ?: error("entity type id $id was never interned")
        ).also { entityTypesById.put(id, it); entityTypeIds.put(it, id) }

    fun restore(unit: StorageUnit) {
        nextHolderId.set(counter(unit, Keys.NS_HOLDER))
        nextItemKeyId.set(counter(unit, Keys.NS_ITEM_KEY))
        nextWorldId.set(counter(unit, Keys.NS_WORLD))
        nextBlockDataId.set(counter(unit, Keys.NS_BLOCK_DATA))
        nextEntityTypeId.set(counter(unit, Keys.NS_ENTITY_TYPE))
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
        fun <K : Any, V : Any> bounded(size: Long): Cache<K, V> =
            Caffeine.newBuilder().maximumSize(size).executor(Runnable::run).build()

        /** ~24 MiB of holders at steady state. */
        const val DEFAULT_CACHE_SIZE = 262_144L
        const val ITEM_KEY_CACHE = 16_384L
        const val WORLD_CACHE = 256L

        /** Distinct block states a server actually uses, with room for every stair rotation. */
        const val BLOCK_DATA_CACHE = 65_536L
        const val ENTITY_TYPE_CACHE = 512L

        /** How many unseen values may be in flight to the storage thread before captures start dropping. */
        const val PROVISIONAL_LIMIT = 65_536
        const val PROVISIONAL_COMPACT_AT = 4_096

        /** Intern counters live in the counter family, above anything the ledger names. */
        const val COUNTER_BASE = 0x7000_0000
    }
}
