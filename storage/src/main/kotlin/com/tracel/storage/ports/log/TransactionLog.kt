package com.tracel.storage.ports.log

import com.tracel.storage.codec.Txn
import com.tracel.storage.codec.Index
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.item.namesMaterial
import com.tracel.model.log.LogKind
import com.tracel.model.log.Seq
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.Transaction
import com.tracel.model.transaction.TxnId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Packed
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.Scalar
import com.tracel.storage.intern.Interning
import com.tracel.storage.util.ascending
import com.tracel.storage.util.eachIndex
import com.tracel.storage.util.eachRow
import com.tracel.storage.util.pageAccepted
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import java.lang.foreign.MemorySegment
import java.util.Arrays
import java.util.stream.IntStream
import com.tracel.engine.log.TransactionLog as TransactionLogPort

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
            check(get(Tag.TXN_BY_ID[transaction.id.raw]) == null) {
                "transaction ${transaction.id} already appended — the log is append-only"
            }

            val seq = transaction.seq.raw
            val causedById = transaction.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val record = ByteArray(Txn.size(transaction.flows.size))
            val into = MemorySegment.ofArray(record)
            val at = transaction.at
            val atWorldId = at?.let { interning.internWorld(this, it.world) } ?: 0
            Txn.writeHeader(
                into,
                transaction.cause,
                transaction.flows.size,
                causedById,
                transaction.id.raw,
                transaction.epochMillis,
                atWorldId,
                at?.x ?: 0,
                at?.y ?: 0,
                at?.z ?: 0,
            )

            val holders = IntOpenHashSet()
            val itemKeys = IntOpenHashSet()
            val bookkeeping = transaction.cause.isBookkeeping
            if (causedById != 0) holders.add(causedById)
            if (!bookkeeping) transaction.causedBy?.let {
                index(
                    this,
                    it,
                    seq,
                    transaction.epochMillis,
                    transaction.cause
                )
            }

            transaction.flows.forEachIndexed { i, flow ->
                val itemKeyId = interning.internItemKey(this, flow.itemKey)
                val sourceId = interning.internHolder(this, flow.source)
                val destinationId = interning.internHolder(this, flow.destination)
                Txn.writeFlow(into, i, itemKeyId, sourceId, destinationId, flow.kind, flow.quantity.raw)
                if (bookkeeping) return@forEachIndexed
                if (holders.add(sourceId)) index(this, flow.source, seq, transaction.epochMillis, transaction.cause)
                if (holders.add(destinationId)) index(
                    this,
                    flow.destination,
                    seq,
                    transaction.epochMillis,
                    transaction.cause
                )
                itemKeys.add(itemKeyId)
            }

            put(Tag.TXN[seq], record)
            put(Tag.TXN_BY_ID[transaction.id.raw], Scalar.long(seq))
            if (bookkeeping) return@write
            val timed = Index.timed(LogKind.TRANSACTION, transaction.epochMillis, transaction.cause)
            for (holderId in holders.toIntArray()) put(Tag.ACTOR[holderId, seq], timed)
            for (itemKeyId in itemKeys.toIntArray()) put(Tag.ITEM[itemKeyId, seq], timed)
            put(Tag.TIME[transaction.epochMillis, seq], OWN_LOG)
            if (at != null && atWorldId != 0) {
                put(
                    Tag.SPATIAL[atWorldId, at.x shr 4, at.z shr 4, transaction.epochMillis, Keys.yBin(at.y), seq],
                    Index.at(LogKind.TRANSACTION, transaction.epochMillis, transaction.cause, at.x, at.y, at.z),
                )
            }
            for ((flowIndex, lotId, quantity) in transaction.lots) {
                put(Tag.TXN_LOT[seq, flowIndex, lotId.raw], Scalar.long(quantity.raw))
            }
        }
    }

    override suspend fun find(id: TxnId): Transaction? = storage.read {
        val seq = get(Tag.TXN_BY_ID[id.raw])?.let(Scalar::asLong) ?: return@read null
        get(Tag.TXN[seq])?.let { decode(this, seq, it) }
    }

    override suspend fun lotsAt(seq: Seq): List<FlowLot> = storage.read {
        val out = ArrayList<FlowLot>()
        eachRow(Tag.TXN_LOT[seq.raw]) { cursor ->
            out += FlowLot(
                cursor.keyU32(9),
                LotId(cursor.keyU64(13)),
                Quantity(Scalar.asLong(cursor.value())),
            )
        }
        out
    }

    override suspend fun lotsAtAll(seqs: List<Seq>): Map<Seq, List<FlowLot>> = storage.read {
        val loaded = loadLots(this, LongArray(seqs.size) { i -> seqs[i].raw })
        val out = HashMap<Seq, List<FlowLot>>(loaded.size)
        for ((seq, lots) in loaded) out[Seq(seq)] = lots
        out
    }

    /** @return the lots at every `seq` in [seqs], newest first. */
    internal fun loadLots(unit: StorageUnit, seqs: LongArray): Map<Long, List<FlowLot>> {
        if (seqs.isEmpty()) return emptyMap()
        val n = seqs.size
        val sorted = seqs.copyOf()
        Arrays.sort(sorted)
        val slices = sliceCount(n)
        QueryProbe.cursors(slices.toLong())
        QueryProbe.scanned(n.toLong())

        val parts = arrayOfNulls<Long2ObjectOpenHashMap<MutableList<FlowLot>>>(slices)
        val take = { s: Int ->
            val out = Long2ObjectOpenHashMap<MutableList<FlowLot>>()
            unit.walkWanted(Tag.TXN_LOT, sorted, n * s / slices, n * (s + 1) / slices) { at, cursor ->
                var lots = out.get(at)
                if (lots == null) {
                    lots = ArrayList()
                    out.put(at, lots)
                }
                lots += FlowLot(
                    cursor.keyU32(9),
                    LotId(cursor.keyU64(13)),
                    Quantity(Scalar.asLong(cursor.value())),
                )
            }
            parts[s] = out
        }
        if (slices > 1) {
            IntStream.range(0, slices).parallel().forEach(take)
        } else {
            take(0)
        }

        if (slices == 1) return parts[0]!!
        val out = Long2ObjectOpenHashMap<MutableList<FlowLot>>(n)
        for (s in 0 until slices) {
            for (entry in parts[s]!!.long2ObjectEntrySet()) {
                val existing = out.putIfAbsent(entry.longKey, entry.value)
                if (existing != null) existing += entry.value
            }
        }
        return out
    }

    override suspend fun query(filter: LookupFilter): List<Transaction> = storage.read {
        val ids = interning.idsFor(this, filter)
        if (ids.matchesNothing) return@read emptyList()
        val holderIds = ids.holders

        val materialIds = itemKeyIdsFor(this, filter)
        if (materialIds != null && materialIds.isEmpty()) return@read emptyList()

        val region = filter.region
        val since = filter.since
        val until = filter.until

        val wanted = filter.limit + filter.offset
        val batched = wanted >= BATCHED_FROM || region != null

        val scans: List<Scan>? = when {
            region != null -> regionScans(this, interning, region, since, until)
            holderIds.isNotEmpty() -> scansOver(holderIds.map { Tag.ACTOR[it] })
            materialIds != null -> scansOver(materialIds.map { Tag.ITEM[it] })
            else -> null
        }
        val matched = LongArrayList()
        val records = ArrayList<MemorySegment>()
        val take = { seq: Long ->
            val record = get(Tag.TXN[seq])
            if (record != null && TransactionFilter.accepts(this, interning, record, filter, ids, materialIds)) {
                matched.add(seq)
                records += record
            }
            matched.size
        }

        if (batched) {
            val seqs = ascending(
                gatherSeqs(LogKind.TRANSACTION, scans, since, until, Int.MAX_VALUE, filter.excludedCauses, region),
            )
            return@read loadSeqs(this, seqs, filter)
        }

        if (scans != null) {
            if (scans.isEmpty()) return@read emptyList()
            mergeDescending(
                scans, LogKind.TRANSACTION, wanted, take,
                sinceMillis = since, untilMillis = until, excludedCauses = filter.excludedCauses,
            )
        } else {
            scanDescendingByTime(LogKind.TRANSACTION, since, until, wanted, take)
        }

        val from = filter.offset.coerceAtMost(matched.size)
        val until2 = (from.toLong() + filter.limit).coerceAtMost(matched.size.toLong()).toInt()
        val out = ArrayList<Transaction>(until2 - from)
        for (i in from until until2) out += decode(this, matched.getLong(i), records[i])
        out
    }

    internal fun loadSeqs(unit: StorageUnit, seqs: LongArray, filter: LookupFilter): List<Transaction> {
        val ids = interning.idsFor(unit, filter)
        val materialIds = itemKeyIdsFor(unit, filter)
        if (seqs.isEmpty()) return emptyList()
        val accepted = pageAccepted(seqs, filter.offset, filter.limit) { slice, keep ->
            QueryProbe.opened(slice.size.toLong())
            val got = unit.fetchSlices(Tag.TXN, slice) { TransactionFilter.accepts(unit, interning, it, filter, ids, materialIds) }
            for (i in 0 until got.seqs.size) keep(got.seqs.getLong(i), got.records[i])
        }
        val n = accepted.seqs.size
        if (n == 0) return emptyList()
        val slots = arrayOfNulls<Transaction>(n)
        eachIndex(n) { i -> slots[i] = decode(unit, accepted.seqs.getLong(i), accepted.records[i]) }
        val out = ArrayList<Transaction>(n)
        for (i in n - 1 downTo 0) out += slots[i]!!
        return out
    }

    /**
     * Interned IDs of the item keys [filter] asks for: its exact keys, or else its material;
     * `null` for any.
     */
    internal fun itemKeyIdsFor(unit: StorageUnit, filter: LookupFilter): IntSet? {
        val keys = filter.itemKeys
        val material = filter.material
        if (keys.isEmpty() && material == null) return null
        val out = IntOpenHashSet()
        unit.eachRow(byteArrayOf(Tag.INTERN_FORWARD.byte, Keys.NS_ITEM_KEY)) { cursor ->
            val key = Packed.decodeItemKey(cursor.value())
            val wanted = if (keys.isNotEmpty()) key in keys else material != null && key.material.namesMaterial(material)
            if (wanted) out.add(KeyReader.u32(cursor.key(), 2))
        }
        return out
    }

    override suspend fun itemKeys(): List<ItemKey> = storage.read {
        val out = ArrayList<ItemKey>()
        eachRow(byteArrayOf(Tag.INTERN_FORWARD.byte, Keys.NS_ITEM_KEY)) { cursor -> out += Packed.decodeItemKey(cursor.value()) }
        out
    }

    private fun index(unit: StorageUnit, holder: HolderId, seq: Long, epochMillis: Long, cause: CauseKind) {
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
        unit.put(
            Tag.SPATIAL[worldId, x shr 4, z shr 4, epochMillis, Keys.yBin(y), seq],
            Index.at(LogKind.TRANSACTION, epochMillis, cause, x, y, z),
        )
    }

    private fun decode(unit: StorageUnit, seq: Long, record: MemorySegment): Transaction {
        val version = Txn.version[record]
        check(version == Scalar.VERSION) {
            "transaction at seq $seq was written by codec v$version, this build reads v${Scalar.VERSION}"
        }
        val flowCount = Txn.flowCount[record]
        val flows = ArrayList<Flow>(flowCount)
        for (i in 0 until flowCount) {
            flows += Flow(
                interning.resolveItemKey(unit, Txn.item[record, i]),
                Quantity(Txn.quantity[record, i]),
                interning.resolveHolder(unit, Txn.source[record, i]),
                interning.resolveHolder(unit, Txn.destination[record, i]),
                Txn.kind[record, i],
            )
        }
        val causedById = Txn.causedBy[record]
        val atWorldId = Txn.world[record]
        return Transaction(
            TxnId(Txn.id[record]),
            Seq(seq),
            Txn.epochMillis[record],
            Txn.cause[record],
            if (causedById == 0) null else interning.resolveHolder(unit, causedById),
            flows,
            at = if (atWorldId == 0) null else BlockPos(
                interning.resolveWorld(unit, atWorldId),
                Txn.x[record],
                Txn.y[record],
                Txn.z[record],
            ),
        )
    }

    private companion object {
        val OWN_LOG = Index.bare(LogKind.TRANSACTION)
    }
}
