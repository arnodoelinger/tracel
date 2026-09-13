package com.tracel.storage.intern

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Packed
import com.tracel.storage.codec.Records
import java.lang.foreign.MemorySegment
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
    private val holders = Interned("holder", Keys.NS_HOLDER, cacheSize, Packed::holder, Packed::decodeHolder)
    private val itemKeys =
        Interned("item key", Keys.NS_ITEM_KEY, ITEM_KEY_CACHE, Packed::itemKey, Packed::decodeItemKey)
    private val worlds = Interned("world", Keys.NS_WORLD, WORLD_CACHE, Packed::world, Packed::decodeWorld)
    private val blockData =
        Interned("block data", Keys.NS_BLOCK_DATA, BLOCK_DATA_CACHE, Packed::blockData, Packed::decodeBlockData)
    private val entityTypes =
        Interned("entity type", Keys.NS_ENTITY_TYPE, ENTITY_TYPE_CACHE, Packed::entityType, Packed::decodeEntityType)

    private val tables = listOf(holders, itemKeys, worlds, blockData, entityTypes)

    // Сapture path: region threads

    private val local = ThreadLocal.withInitial { ThreadCache() }

    fun holderIdForCapture(holder: HolderId): Int = forCapture(holders, holder)

    fun itemKeyIdForCapture(itemKey: ItemKey): Int = forCapture(itemKeys, itemKey)

    fun blockDataIdForCapture(blockData: BlockDataKey): Int = forCapture(this.blockData, blockData)

    fun worldIdForCapture(world: WorldId): Int = forCapture(worlds, world)

    private fun <T : Any> forCapture(table: Interned<T>, value: T): Int {
        val cache = local.get()
        val cached = cache.lookup(value)
        if (cached != 0) return cached
        val id = table.cached(value) ?: return provisional(value)
        cache.store(value, id)
        return id
    }

    /**
     * A direct-mapped cache per thread, in front of the shared one.
     *
     * A region thread sees the same handful of holders over and over... One hopper, its chest,
     * one item key — and a shared bounded cache still costs a hash, a table probe and an
     * eviction-policy record for every one of them. This is an array index and an `equals`.
     */
    private class ThreadCache {
        private val keys = arrayOfNulls<Any>(SLOTS)
        private val ids = IntArray(SLOTS)

        /** @return the id for [value], or zero if it is not in this thread's cache. */
        fun lookup(value: Any): Int {
            val slot = slotOf(value)
            return if (keys[slot] == value) ids[slot] else 0
        }

        /** Stores the mapping from [value] to [id] in this thread's cache. */
        fun store(value: Any, id: Int) {
            val slot = slotOf(value)
            keys[slot] = value
            ids[slot] = id
        }

        private fun slotOf(value: Any): Int {
            val hash = value.hashCode()
            return (hash xor (hash ushr 16)) and (SLOTS - 1)
        }

        private companion object {
            const val SLOTS = 256
        }
    }

    // Provisional IDs: the hand-off from a region thread to the storage thread

    private val provisionalIds = ConcurrentHashMap<Any, Int>()
    private val provisionalValues = ConcurrentHashMap<Int, Any>()
    private val provisionalToReal = ConcurrentHashMap<Int, Int>()
    private val nextProvisional = AtomicInteger(0)
    private val drops = AtomicLong(0)

    val droppedForCapacity: Long get() = drops.get()

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

    /** Resolves a provisional id to a real one, interning the value if it has to. */
    fun canonical(unit: StorageUnit, id: Int): Int {
        if (id >= 0) return id
        provisionalToReal[id]?.let { return it }
        val value = provisionalValues[id] ?: return 0
        val real = when (value) {
            is HolderId -> holders.intern(unit, value)
            is ItemKey -> itemKeys.intern(unit, value)
            is WorldId -> worlds.intern(unit, value)
            is BlockDataKey -> blockData.intern(unit, value)
            is EntityTypeKey -> entityTypes.intern(unit, value)
            else -> error("nothing else is ever interned provisionally: ${value::class}")
        }
        provisionalToReal[id] = real
        return real
    }

    /**
     * Drops every provisional value and mapping, once there are enough of them to be worth it.
     *
     * Safe at any point where nothing holds an unresolved provisional id: every one of them has
     * already been canonicalized into a real ID, and a value that turns up again simply gets a
     * fresh placeholder.
     */
    fun compactProvisional() {
        if (provisionalValues.size < PROVISIONAL_COMPACT_AT) return
        provisionalIds.clear()
        provisionalValues.clear()
        provisionalToReal.clear()
    }

    // Storage path: the writer thread, inside an open unit

    fun internHolder(unit: StorageUnit, holder: HolderId): Int = holders.intern(unit, holder)

    fun internItemKey(unit: StorageUnit, itemKey: ItemKey): Int = itemKeys.intern(unit, itemKey)

    fun internWorld(unit: StorageUnit, world: WorldId): Int = worlds.intern(unit, world)

    fun internBlockData(unit: StorageUnit, blockData: BlockDataKey): Int = this.blockData.intern(unit, blockData)

    fun internEntityType(unit: StorageUnit, entityType: EntityTypeKey): Int = entityTypes.intern(unit, entityType)

    fun findHolderId(unit: StorageUnit, holder: HolderId): Int? = holders.find(unit, holder)

    fun findItemKeyId(unit: StorageUnit, itemKey: ItemKey): Int? = itemKeys.find(unit, itemKey)

    fun findWorldId(unit: StorageUnit, world: WorldId): Int? = worlds.find(unit, world)

    fun resolveHolder(unit: StorageUnit, id: Int): HolderId = holders.resolve(unit, id)

    fun resolveItemKey(unit: StorageUnit, id: Int): ItemKey = itemKeys.resolve(unit, id)

    fun resolveWorld(unit: StorageUnit, id: Int): WorldId = worlds.resolve(unit, id)

    fun resolveBlockData(unit: StorageUnit, id: Int): BlockDataKey = blockData.resolve(unit, id)

    fun resolveEntityType(unit: StorageUnit, id: Int): EntityTypeKey = entityTypes.resolve(unit, id)

    fun restore(unit: StorageUnit) {
        for (table in tables) table.restore(unit)
    }

    private companion object {
        const val DEFAULT_CACHE_SIZE = 262_144L
        const val ITEM_KEY_CACHE = 16_384L
        const val WORLD_CACHE = 256L
        const val BLOCK_DATA_CACHE = 65_536L
        const val ENTITY_TYPE_CACHE = 512L

        const val PROVISIONAL_LIMIT = 65_536
        const val PROVISIONAL_COMPACT_AT = 4_096
    }
}

/** One kind of value and its IDs, directions. Cached. */
private class Interned<T : Any>(
    private val name: String,
    private val namespace: Byte,
    cacheSize: Long,
    private val pack: (T) -> ByteArray,
    private val decode: (MemorySegment) -> T,
) {
    private val byValue: Cache<T, Int> = bounded(cacheSize)
    private val byId: Cache<Int, T> = bounded(cacheSize)
    private val counter = AtomicInteger(0)

    /** What the shared cache already knows, without touching the store. The capture path's second look. */
    fun cached(value: T): Int? = byValue.getIfPresent(value)

    /** The id [value] has, or null. Reads the store, writes nothing but the cache. */
    fun find(unit: StorageUnit, value: T): Int? =
        byValue.getIfPresent(value) ?: idOf(unit, pack(value))?.also { remember(value, it) }

    /** The id [value] has, assigning and writing one if it has none. */
    fun intern(unit: StorageUnit, value: T): Int {
        byValue.getIfPresent(value)?.let { return it }
        val packed = pack(value)
        idOf(unit, packed)?.let {
            remember(value, it)
            return it
        }

        val id = counter.incrementAndGet()
        unit.putPinned(Keys.internForward(namespace, id), packed)
        unit.putPinned(Keys.internReverse(namespace, packed), Records.int(id))
        unit.putPinned(Keys.counter(COUNTER_BASE + namespace), Records.long(id.toLong()))
        remember(value, id)
        return id
    }

    /** The value behind [id]. */
    fun resolve(unit: StorageUnit, id: Int): T =
        byId.getIfPresent(id) ?: decode(
            unit.get(Keys.internForward(namespace, id)) ?: error("$name id $id was never interned"),
        ).also { remember(it, id) }

    /** Restores the counter from the store. */
    fun restore(unit: StorageUnit) {
        counter.set(unit.get(Keys.counter(COUNTER_BASE + namespace))?.let(Records::asLong)?.toInt() ?: 0)
    }

    private fun idOf(unit: StorageUnit, packed: ByteArray): Int? =
        unit.get(Keys.internReverse(namespace, packed))?.let(Records::asInt)

    private fun remember(value: T, id: Int) {
        byValue.put(value, id)
        byId.put(id, value)
    }

    private companion object {
        fun <K : Any, V : Any> bounded(size: Long): Cache<K, V> =
            Caffeine.newBuilder().maximumSize(size).executor(Runnable::run).build()

        const val COUNTER_BASE = 0x7000_0000
    }
}
