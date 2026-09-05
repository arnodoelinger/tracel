package com.tracel.storage.ports.log

import com.tracel.annotations.CauseKind
import com.tracel.annotations.isBookkeeping
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.LookupRegion
import com.tracel.engine.log.TransactionLog as TransactionLogPort
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.id.WorldId
import com.tracel.model.item.namesMaterial
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.BlockPos
import com.tracel.model.world.LogKind
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Packed
import com.tracel.storage.codec.Records
import com.tracel.storage.intern.Interning
import com.tracel.storage.util.ascending
import com.tracel.storage.util.collectNewestFirst
import com.tracel.storage.util.eachIndex
import com.tracel.storage.util.eachRow
import com.tracel.storage.util.pageNewest
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

    /** Appends [transaction] to the log, and indexes it for search. */
    override suspend fun append(transaction: Transaction) {
        storage.write {
            check(get(Keys.txnById(transaction.id.raw)) == null) {
                "transaction ${transaction.id} already appended — the log is append-only"
            }

            val seq = transaction.seq.raw
            val causedById = transaction.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val record = ByteArray(Records.transactionSize(transaction.flows.size))
            val into = MemorySegment.ofArray(record)
            val at = transaction.at
            val atWorldId = at?.let { interning.internWorld(this, it.world) } ?: 0
            Records.writeTransactionHeader(
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

            val holders = HashSet<Int>()
            val itemKeys = HashSet<Int>()
            val bookkeeping = transaction.cause.isBookkeeping
            if (causedById != 0) holders += causedById
            if (!bookkeeping) transaction.causedBy?.let { index(this, it, seq, transaction.epochMillis, transaction.cause) }

            transaction.flows.forEachIndexed { i, flow ->
                val itemKeyId = interning.internItemKey(this, flow.itemKey)
                val sourceId = interning.internHolder(this, flow.source)
                val destinationId = interning.internHolder(this, flow.destination)
                Records.writeFlow(into, i, itemKeyId, sourceId, destinationId, flow.kind, flow.quantity.raw)
                if (bookkeeping) return@forEachIndexed
                if (holders.add(sourceId)) index(this, flow.source, seq, transaction.epochMillis, transaction.cause)
                if (holders.add(destinationId)) index(this, flow.destination, seq, transaction.epochMillis, transaction.cause)
                itemKeys += itemKeyId
            }

            put(Keys.txn(seq), record)
            put(Keys.txnById(transaction.id.raw), Records.long(seq))
            if (bookkeeping) return@write
            for (holderId in holders) put(Keys.actor(holderId, seq), OWN_LOG)
            for (itemKeyId in itemKeys) put(Keys.item(itemKeyId, seq), OWN_LOG)
            put(Keys.time(transaction.epochMillis, seq), OWN_LOG)
            if (at != null && atWorldId != 0) {
                put(
                    Keys.spatial(atWorldId, at.x shr 4, at.z shr 4, at.y, seq, transaction.epochMillis),
                    Records.logKind(LogKind.TRANSACTION, transaction.epochMillis, transaction.cause, at.x, at.y, at.z),
                )
            }
            for ((flowIndex, lotId, quantity) in transaction.lots) {
                put(Keys.txnLot(seq, flowIndex, lotId.raw), Records.long(quantity.raw))
            }
        }
    }

    /** @return the transaction with [id], or null if it doesn't exist. */
    override suspend fun find(id: TxnId): Transaction? = storage.read {
        val seq = get(Keys.txnById(id.raw))?.let(Records::asLong) ?: return@read null
        get(Keys.txn(seq))?.let { decode(this, seq, it) }
    }

    /** @return the transaction at [seq], or null if it doesn't exist. */
    override suspend fun lotsAt(seq: Seq): List<FlowLot> = storage.read {
        val out = ArrayList<FlowLot>()
        eachRow(Keys.txnLotPrefix(seq.raw)) { cursor ->
            out += FlowLot(
                cursor.keyU32(9),
                LotId(cursor.keyU64(13)),
                Quantity(Records.asLong(cursor.value())),
            )
        }
        out
    }

    /** @return the lots at every `seq` in [seqs], newest first. */
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
        java.util.Arrays.sort(sorted)
        val slices = sliceCount(n)
        QueryProbe.cursors(slices.toLong())
        QueryProbe.scanned(n.toLong())

        val parts = arrayOfNulls<HashMap<Long, MutableList<FlowLot>>>(slices)
        val take = { s: Int ->
            val out = HashMap<Long, MutableList<FlowLot>>()
            unit.walkWanted(
                Keys.TXN_LOT, sorted, n * s / slices, n * (s + 1) / slices, Keys::txnLotPrefix,
            ) { at, cursor ->
                out.getOrPut(at) { ArrayList() } += FlowLot(
                    cursor.keyU32(9),
                    LotId(cursor.keyU64(13)),
                    Quantity(Records.asLong(cursor.value())),
                )
            }
            parts[s] = out
        }
        if (slices > 1) {
            java.util.stream.IntStream.range(0, slices).parallel().forEach(take)
        } else {
            take(0)
        }

        if (slices == 1) return parts[0]!!
        val out = HashMap<Long, MutableList<FlowLot>>(n)
        for (s in 0 until slices) {
            for ((at, lots) in parts[s]!!) {
                val existing = out.putIfAbsent(at, lots)
                if (existing != null) existing += lots
            }
        }
        return out
    }

    /** @return the transactions that match [filter], newest first. */
    override suspend fun query(filter: LookupFilter): List<Transaction> = storage.read {
        val ids = interning.idsFor(this, filter)
        if (ids.matchesNothing) return@read emptyList()
        val holderIds = ids.holders

        val materialIds = filter.material?.let { itemKeyIdsFor(this, it) }
        if (materialIds != null && materialIds.isEmpty()) return@read emptyList()

        val region = filter.region
        val since = filter.since
        val until = filter.until

        val wanted = filter.limit + filter.offset
        val batched = wanted >= BATCHED_FROM || region != null

        val scans: List<Scan>? = when {
            region != null -> regionScans(this, interning, region, since, until)
            holderIds.isNotEmpty() -> scansOver(holderIds.map(Keys::actorPrefix))
            materialIds != null -> scansOver(materialIds.map(Keys::itemPrefix))
            else -> null
        }
        val matched = ArrayList<Long>()
        val records = ArrayList<MemorySegment>()
        val take = { seq: Long ->
            val record = get(Keys.txn(seq))
            if (record != null && accepts(record, filter, ids, materialIds)) {
                matched += seq
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
        for (i in from until until2) out += decode(this, matched[i], records[i])
        out
    }

    internal fun loadSeqs(unit: StorageUnit, seqs: LongArray, filter: LookupFilter): List<Transaction> {
        val ids = interning.idsFor(unit, filter)
        val materialIds = filter.material?.let { itemKeyIdsFor(unit, it) }
        val page = pageNewest(seqs, filter.offset, filter.limit)
        if (page.isEmpty()) return emptyList()
        QueryProbe.opened(page.size.toLong())
        val matched = ArrayList<Long>(page.size)
        val records = ArrayList<MemorySegment>(page.size)
        if (worthScanning(page)) {
            QueryProbe.scanned(page.size.toLong())
            unit.fetchAscending(Keys.TXN, page, Keys::txn) { seq, record ->
                if (unit.accepts(record, filter, ids, materialIds)) {
                    matched += seq
                    records += record
                }
            }
            val n = matched.size
            if (n == 0) return emptyList()
            val slots = arrayOfNulls<Transaction>(n)
            eachIndex(n) { i -> slots[i] = decode(unit, matched[i], records[i]) }
            val out = ArrayList<Transaction>(n)
            for (i in n - 1 downTo 0) out += slots[i]!!
            return out
        }
        QueryProbe.pointGot(page.size.toLong())
        return collectNewestFirst(page) { seq ->
            val record = unit.get(Keys.txn(seq)) ?: return@collectNewestFirst null
            if (!unit.accepts(record, filter, ids, materialIds)) {
                return@collectNewestFirst null
            }
            decode(unit, seq, record)
        }
    }

    private fun StorageUnit.accepts(
        record: MemorySegment,
        filter: LookupFilter,
        ids: FilterIds,
        materialIds: Set<Int>?,
    ): Boolean {
        val holderIds = ids.holders
        val excludedIds = ids.excluded
        val regionWorldId = ids.regionWorld
        val filterWorldId = ids.world
        val epochMillis = Records.txnEpochMillis(record)
        val since = filter.since
        val until = filter.until
        if (since != null && epochMillis < since) return false
        if (until != null && epochMillis > until) return false
        val cause = Records.txnCause(record)
        if (cause.isBookkeeping) return false
        if (filter.causes.isNotEmpty() && cause !in filter.causes) return false
        if (filter.excludedCauses.isNotEmpty() && cause in filter.excludedCauses) return false

        val flowCount = Records.txnFlowCount(record)
        if (holderIds.isNotEmpty()) {
            var hit = Records.txnCausedBy(record) in holderIds
            if (!hit) {
                for (i in 0 until flowCount) {
                    if (Records.flowSource(record, i) in holderIds || Records.flowDestination(record, i) in holderIds) {
                        hit = true
                        break
                    }
                }
            }
            if (!hit) return false
        }
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
        if (filterWorldId != null && !inWorld(record, flowCount, filterWorldId)) return false
        val region = filter.region
        if (region != null) {
            if (regionWorldId == null || !inRegion(record, flowCount, region, regionWorldId)) return false
        }
        return true
    }

    private fun StorageUnit.inRegion(
        record: MemorySegment,
        flowCount: Int,
        region: LookupRegion,
        regionWorldId: Int,
    ): Boolean {
        if (Records.txnWorldId(record) == regionWorldId &&
            region.containsBlock(Records.txnX(record), Records.txnY(record), Records.txnZ(record))
        ) {
            return true
        }
        for (i in 0 until flowCount) {
            if (holderInRegion(Records.flowSource(record, i), region, regionWorldId)) return true
            if (holderInRegion(Records.flowDestination(record, i), region, regionWorldId)) return true
        }
        return false
    }

    private fun StorageUnit.inWorld(record: MemorySegment, flowCount: Int, worldId: Int): Boolean {
        if (Records.txnWorldId(record) == worldId) return true
        for (i in 0 until flowCount) {
            if (holderWorldId(Records.flowSource(record, i)) == worldId) return true
            if (holderWorldId(Records.flowDestination(record, i)) == worldId) return true
        }
        return false
    }

    private fun StorageUnit.holderInRegion(id: Int, region: LookupRegion, regionWorldId: Int): Boolean {
        val at = holderBlock(id) ?: return false
        return at.worldId == regionWorldId && region.containsBlock(at.x, at.y, at.z)
    }

    private fun StorageUnit.holderWorldId(id: Int): Int? = holderBlock(id)?.worldId

    private fun StorageUnit.holderBlock(id: Int): HolderBlock? {
        if (id == 0) return null
        return when (val holder = interning.resolveHolder(this, id)) {
            is HolderId.Block -> HolderBlock(
                interning.findWorldId(this, holder.world) ?: return null,
                holder.x, holder.y, holder.z,
            )
            is HolderId.PlacedBlock -> HolderBlock(
                interning.findWorldId(this, holder.world) ?: return null,
                holder.x, holder.y, holder.z,
            )
            else -> null
        }
    }

    private data class HolderBlock(val worldId: Int, val x: Int, val y: Int, val z: Int)

    private fun itemKeyIdsFor(unit: StorageUnit, material: String): Set<Int> {
        val out = HashSet<Int>()
        unit.eachRow(byteArrayOf(Keys.INTERN_FORWARD, Keys.NS_ITEM_KEY)) { cursor ->
            if (Packed.decodeItemKey(cursor.value()).material.namesMaterial(material)) {
                out += KeyReader.u32(cursor.key(), 2)
            }
        }
        return out
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
            Keys.spatial(worldId, x shr 4, z shr 4, y, seq, epochMillis),
            Records.logKind(LogKind.TRANSACTION, epochMillis, cause, x, y, z),
        )
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
        val atWorldId = Records.txnWorldId(record)
        return Transaction(
            TxnId(Records.txnId(record)),
            Seq(seq),
            Records.txnEpochMillis(record),
            Records.txnCause(record),
            if (causedById == 0) null else interning.resolveHolder(unit, causedById),
            flows,
            at = if (atWorldId == 0) null else BlockPos(
                interning.resolveWorld(unit, atWorldId),
                Records.txnX(record),
                Records.txnY(record),
                Records.txnZ(record),
            ),
        )
    }

    private companion object {
        val OWN_LOG = Records.logKind(LogKind.TRANSACTION)
    }
}
