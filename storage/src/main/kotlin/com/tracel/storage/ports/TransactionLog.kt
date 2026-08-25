package com.tracel.storage.ports

import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.LookupRegion
import com.tracel.engine.log.TransactionLog as TransactionLogPort
import com.tracel.model.flow.Flow
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.WorldId
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Packed
import com.tracel.storage.codec.Records
import com.tracel.storage.intern.Interning
import java.lang.foreign.MemorySegment

/**
 * The append-only log, plus the four indexes that make it searchable.
 *
 * A transaction is one record — header and every flow packed contiguously — under its sequence
 * number, and four secondary index entries per distinct thing it touches: the holders, the item
 * keys, the timestamp, and the chunk columns.
 *
 * Every one of those stores the sequence inverted, so "newest first" is a forward prefix scan and
 * the engine never needs a backwards cursor.
 */
class TransactionLog(private val storage: TracelStorage) : TransactionLogPort {
    private val interning: Interning get() = storage.interning

    override suspend fun append(transaction: Transaction) {
        storage.write {
            check(get(Keys.txnById(transaction.id.raw)) == null) {
                "transaction ${transaction.id} already appended — the log is append-only"
            }

            val seq = transaction.seq.raw
            val causedById = transaction.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val record = ByteArray(Records.transactionSize(transaction.flows.size))
            val into = MemorySegment.ofArray(record)
            Records.writeTransactionHeader(
                into,
                transaction.cause,
                transaction.flows.size,
                causedById,
                transaction.id.raw,
                transaction.epochMillis,
            )

            val holders = HashSet<Int>()
            val itemKeys = HashSet<Int>()
            if (causedById != 0) holders += causedById
            transaction.causedBy?.let { index(this, it, seq) }

            transaction.flows.forEachIndexed { i, flow ->
                val itemKeyId = interning.internItemKey(this, flow.itemKey)
                val sourceId = interning.internHolder(this, flow.source)
                val destinationId = interning.internHolder(this, flow.destination)
                Records.writeFlow(into, i, itemKeyId, sourceId, destinationId, flow.kind, flow.quantity.raw)
                if (holders.add(sourceId)) index(this, flow.source, seq)
                if (holders.add(destinationId)) index(this, flow.destination, seq)
                itemKeys += itemKeyId
            }

            put(Keys.txn(seq), record)
            put(Keys.txnById(transaction.id.raw), Records.long(seq))
            for (holderId in holders) put(Keys.actor(holderId, seq), EMPTY)
            for (itemKeyId in itemKeys) put(Keys.item(itemKeyId, seq), EMPTY)
            put(Keys.time(transaction.epochMillis, seq), EMPTY)
        }
    }

    override suspend fun find(id: TxnId): Transaction? = storage.read {
        val seq = get(Keys.txnById(id.raw))?.let(Records::asLong) ?: return@read null
        get(Keys.txn(seq))?.let { decode(this, seq, it) }
    }

    override suspend fun query(filter: LookupFilter): List<Transaction> = storage.read {
        val holderIds = filter.holders.mapNotNull { interning.findHolderId(this, it) }
        if (filter.holders.isNotEmpty() && holderIds.isEmpty()) return@read emptyList()
        val excludedIds = filter.excludedHolders.mapNotNull { interning.findHolderId(this, it) }.toSet()

        val materialIds = filter.material?.let { itemKeyIdsFor(this, it) }
        if (materialIds != null && materialIds.isEmpty()) return@read emptyList()

        val region = filter.region
        val since = filter.since
        val until = filter.until

        val driver: Driver = when {
            holderIds.isNotEmpty() -> Driver.Index(holderIds.map(Keys::actorPrefix))
            region != null -> Driver.Index(regionPrefixes(this, region))
            materialIds != null -> Driver.Index(materialIds.map(Keys::itemPrefix))
            else -> Driver.Time
        }

        val wanted = filter.limit + filter.offset
        val matched = ArrayList<Long>()

        when (driver) {
            is Driver.Time -> {
                val prefix = byteArrayOf(Keys.TIME)
                val from = if (until != null) Keys.timeFrom(until) else prefix
                scan(prefix, from).use { cursor ->
                    while (cursor.next() && matched.size < wanted) {
                        val key = cursor.key()
                        val epochMillis = Keys.invert(KeyReader.u64(key, 1))
                        if (since != null && epochMillis < since) break
                        val seq = Keys.invert(KeyReader.u64(key, 9))
                        if (accepts(this, seq, filter, materialIds, excludedIds)) matched += seq
                    }
                }
            }

            is Driver.Index -> {
                if (driver.prefixes.isEmpty()) return@read emptyList()
                mergeDescending(driver.prefixes, wanted) { seq ->
                    if (accepts(this, seq, filter, materialIds, excludedIds)) matched += seq
                    matched.size
                }
            }
        }

        matched.asSequence()
            .drop(filter.offset)
            .take(filter.limit)
            .mapNotNull { seq -> get(Keys.txn(seq))?.let { decode(this, seq, it) } }
            .toList()
    }

    private sealed interface Driver {
        data object Time : Driver
        data class Index(val prefixes: List<ByteArray>) : Driver
    }

    private inline fun StorageUnit.mergeDescending(
        prefixes: List<ByteArray>,
        wanted: Int,
        accept: (Long) -> Int,
    ) {
        val cursors = Array(prefixes.size) { scan(prefixes[it]) }
        try {
            val heads = LongArray(cursors.size)
            for (i in cursors.indices) heads[i] = headOf(cursors[i])

            var previous = -1L
            while (true) {
                var best = -1
                for (i in heads.indices) if (heads[i] >= 0 && (best < 0 || heads[i] > heads[best])) best = i
                if (best < 0) break

                val seq = heads[best]
                heads[best] = headOf(cursors[best])
                if (seq == previous) continue
                previous = seq
                if (accept(seq) >= wanted) break
            }
        } finally {
            cursors.forEach { it.close() }
        }
    }

    private fun headOf(cursor: com.tracel.storage.spi.EngineCursor): Long {
        if (!cursor.next()) return -1
        val key = cursor.key()
        return Keys.invert(KeyReader.u64(key, key.size - 8))
    }

    private fun accepts(
        unit: StorageUnit,
        seq: Long,
        filter: LookupFilter,
        materialIds: Set<Int>?,
        excludedIds: Set<Int>,
    ): Boolean {
        val record = unit.get(Keys.txn(seq)) ?: return false
        val epochMillis = Records.txnEpochMillis(record)
        val since = filter.since
        val until = filter.until
        if (since != null && epochMillis < since) return false
        if (until != null && epochMillis > until) return false
        if (filter.causes.isNotEmpty() && Records.txnCause(record) !in filter.causes) return false

        val flowCount = Records.txnFlowCount(record)
        if (excludedIds.isNotEmpty()) {
            if (Records.txnCausedBy(record) in excludedIds) return false
            for (i in 0 until flowCount) {
                if (Records.flowSource(record, i) in excludedIds) return false
                if (Records.flowDestination(record, i) in excludedIds) return false
            }
        }
        if (materialIds != null) {
            var hit = false
            for (i in 0 until flowCount) {
                if (Records.flowItemKeyId(record, i) in materialIds) {
                    hit = true
                    break
                }
            }
            if (!hit) return false
        }
        return true
    }

    private fun itemKeyIdsFor(unit: StorageUnit, material: String): Set<Int> {
        val out = HashSet<Int>()
        unit.scan(byteArrayOf(Keys.INTERN_FORWARD, Keys.NS_ITEM_KEY)).use { cursor ->
            while (cursor.next()) {
                if (Packed.decodeItemKey(cursor.value()).material == material) {
                    out += KeyReader.u32(cursor.key(), 2)
                }
            }
        }
        return out
    }

    private fun regionPrefixes(unit: StorageUnit, region: LookupRegion): List<ByteArray> {
        val worldId = interning.internWorld(unit, region.world)
        val out = ArrayList<ByteArray>(region.chunks)
        for (x in region.minChunkX..region.maxChunkX) {
            for (z in region.minChunkZ..region.maxChunkZ) {
                out += Keys.spatialChunkPrefix(worldId, x, z)
            }
        }
        return out
    }

    private fun index(unit: StorageUnit, holder: HolderId, seq: Long) {
        val world: WorldId
        val x: Int
        val y: Int
        val z: Int
        when (holder) {
            is HolderId.Block -> {
                world = holder.world; x = holder.x; y = holder.y; z = holder.z
            }

            is HolderId.PlacedBlock -> {
                world = holder.world; x = holder.x; y = holder.y; z = holder.z
            }

            else -> return
        }
        val worldId = interning.internWorld(unit, world)
        unit.put(Keys.spatial(worldId, x shr 4, z shr 4, y, seq), EMPTY)
    }

    private fun decode(unit: StorageUnit, seq: Long, record: MemorySegment): Transaction {
        val version = Records.txnVersion(record)
        check(version == Records.VERSION) {
            "transaction at seq $seq was written by codec v$version, this build reads v${Records.VERSION}"
        }
        val flowCount = Records.txnFlowCount(record)
        val flows = ArrayList<Flow>(flowCount)
        for (i in 0 until flowCount) {
            flows += Flow(
                interning.resolveItemKey(unit, Records.flowItemKeyId(record, i)),
                Quantity(Records.flowQuantity(record, i)),
                interning.resolveHolder(unit, Records.flowSource(record, i)),
                interning.resolveHolder(unit, Records.flowDestination(record, i)),
                Records.flowKind(record, i),
            )
        }
        val causedById = Records.txnCausedBy(record)
        return Transaction(
            TxnId(Records.txnId(record)),
            Seq(seq),
            Records.txnEpochMillis(record),
            Records.txnCause(record),
            if (causedById == 0) null else interning.resolveHolder(unit, causedById),
            flows,
        )
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
