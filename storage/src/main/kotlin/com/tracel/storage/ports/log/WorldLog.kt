package com.tracel.storage.ports.log

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.log.LogKind
import com.tracel.model.log.Seq
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.*
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.RawBlockEdits
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.Section
import com.tracel.storage.intern.Interning
import com.tracel.storage.util.Accepted
import com.tracel.storage.util.ascending
import com.tracel.storage.util.pageAccepted
import it.unimi.dsi.fastutil.longs.LongArrayList
import java.lang.foreign.MemorySegment
import java.util.Arrays
import java.util.stream.IntStream
import com.tracel.engine.log.TransactionLog as TransactionLogPort
import com.tracel.engine.world.WorldLog as WorldLogPort

/**
 * The world-change log, plus the indexes that make it searchable.
 *
 * One record per change under its sequence number, one per-coordinate entry so "what stood here
 * before" is a prefix scan of a single block, and entries in the actor, time and spatial families
 * — which it shares with [TransactionLog], numbering out of the same counter, so a region scan
 * returns block edits and item movements already interleaved in the order they happened.
 *
 * Sequences are stored inverted everywhere, so newest-first is a forward scan and the engine
 * still never needs a backwards cursor.
 */
class WorldLog(private val storage: TracelStorage) : WorldLogPort {
    private val interning: Interning get() = storage.interning
    private val writer = WorldWriter(storage)
    private val decoder = WorldDecoder(storage.interning)
    private val pages = WorldPages(decoder, storage.interning)

    override suspend fun append(change: WorldChange) = writer.append(change)

    override suspend fun appendAll(edits: BlockEdits, nextSeqRange: suspend (Int) -> Seq): Int =
        writer.appendAll(edits, nextSeqRange)

    /**
     * [appendAll] for edits that never left their interned IDs: no shape is resolved only to be interned again,
     * the whole event takes one range of sequences and lands in one write.
     */
    suspend fun appendRaw(raw: RawBlockEdits, nextSeqRange: suspend (Int) -> Seq): Int = writer.appendRaw(raw, nextSeqRange)

    override suspend fun at(pos: BlockPos, limit: Int): List<WorldChange> = storage.read {
        val worldId = interning.findWorldId(this, pos.world) ?: return@read emptyList()
        val single = ArrayList<WorldChange>(limit.coerceAtMost(INITIAL_CAPACITY))
        scan(Tag.WCHG_AT[worldId, pos.x, pos.y, pos.z]).use { cursor ->
            while (single.size < limit && cursor.next()) {
                val seq = Keys.invert(cursor.keyU64(17))
                get(Tag.WCHG[seq])?.let { single += decoder.decode(this, seq, it) }
            }
        }

        val packed = Section.position(pos.x, pos.y, pos.z)
        val fromSections = ArrayList<WorldChange>()
        scan(Tag.WCHG_AT_SECTION[worldId, pos.x shr 4, pos.y shr 4, pos.z shr 4]).use { cursor ->
            while (fromSections.size < limit && cursor.next()) {
                val baseSeq = Keys.invert(cursor.keyU64(17))
                val record = get(Tag.WCHG[baseSeq]) ?: continue
                val index = Section.indexOf(record, packed)
                if (index < 0) continue
                fromSections += decoder.sectionChangeAt(this, record, index, packed)
            }
        }

        if (fromSections.isEmpty()) return@read single
        if (single.isEmpty()) return@read fromSections.take(limit)

        val out = ArrayList<WorldChange>(limit.coerceAtMost(single.size + fromSections.size))
        var a = 0
        var b = 0
        while (out.size < limit && (a < single.size || b < fromSections.size)) {
            out += when {
                a == single.size -> fromSections[b++]
                b == fromSections.size -> single[a++]
                single[a].seq.raw >= fromSections[b].seq.raw -> single[a++]
                else -> fromSections[b++]
            }
        }
        out
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun queryTogether(
        transactions: TransactionLogPort,
        filter: LookupFilter,
        includeWorld: Boolean,
        structureEnds: Boolean,
    ): Pair<List<WorldChange>, List<Transaction>> = storage.read {
        val ids = interning.idsFor(this, filter)
        if (ids.matchesNothing) return@read emptyList<WorldChange>() to emptyList()
        val holderIds = ids.holders
        val filterWorldId = ids.world
        val regionWorldId = ids.regionWorld
        val region = filter.region
        val scans: List<Scan>? = when {
            region != null -> regionScans(this, interning, region, filter.since, filter.until)
            holderIds.isNotEmpty() -> scansOver(holderIds.map { Tag.ACTOR[it] })
            else -> null
        }
        val inlineWorld = includeWorld && scans != null && region != null && regionWorldId != null &&
                (filterWorldId == null || filterWorldId == regionWorldId) &&
                filter.material == null && filter.itemKeys.isEmpty() && filter.offset == 0 && filter.limit == Int.MAX_VALUE
        val worldRows = if (inlineWorld) SpatialRows() else null

        val piped = scans == null && includeWorld && filter.offset == 0 && filter.limit == Int.MAX_VALUE
        var fetched: Accepted? = null
        val (worldSeqs, txnSeqs) = when {
            scans != null && scans.isEmpty() -> return@read emptyList<WorldChange>() to emptyList()
            piped -> QueryProbe.phase("index + fetch") {
                val (world, txns) = fetchByTime(filter.since, filter.until, filter.excludedCauses) { record ->
                    WorldFilter.accepts(this, interning, record, filter, ids)
                }
                fetched = world
                LongArrayList() to txns
            }

            else -> QueryProbe.phase("index") {
                gatherForFilter(scans, filter.since, filter.until, filter.excludedCauses, region, worldRows)
            }
        }
        val worldArr = if (inlineWorld || piped) LongArray(0) else ascending(worldSeqs)
        val txnLog = transactions as TransactionLog
        val byItem =
            if (scans == null) txnLog.itemKeyIdsFor(this, filter) else null
        val txnArr = if (byItem == null) ascending(txnSeqs) else ascending(
            QueryProbe.phase("item index") {
                gatherSeqs(
                    LogKind.TRANSACTION,
                    scansOver(byItem.map { Tag.ITEM[it] }),
                    filter.since,
                    filter.until,
                    Int.MAX_VALUE,
                    filter.excludedCauses,
                    null
                )
            },
        )
        val unit = this
        val slot = arrayOfNulls<Any>(2)
        IntStream.range(0, 2).parallel().forEach { i ->
            if (i == 0) {
                slot[0] = when {
                    !includeWorld -> emptyList()
                    worldRows != null -> QueryProbe.phase("world rows") {
                        pages.fromRows(unit, worldRows, filter, ids, regionWorldId!!, structureEnds)
                    }

                    fetched != null -> QueryProbe.phase("world records") {
                        pages.decodeAccepted(unit, fetched!!, filter, structureEnds)
                    }

                    else -> QueryProbe.phase("world records") {
                        loadSeqs(unit, worldArr, filter, ids, structureEnds)
                    }
                }
            } else {
                val loaded = QueryProbe.phase("txn records") { txnLog.loadSeqs(unit, txnArr, filter) }
                val lots = QueryProbe.phase("txn lots") {
                    txnLog.loadLots(unit, LongArray(loaded.size) { loaded[it].seq.raw }.also(Arrays::sort))
                }
                slot[1] = if (lots.isEmpty()) loaded else loaded.map { txn ->
                    lots[txn.seq.raw]?.let { txn.copy(lots = it) } ?: txn
                }
            }
        }
        (slot[0] as List<WorldChange>) to (slot[1] as List<Transaction>)
    }

    override suspend fun query(filter: LookupFilter): List<WorldChange> = storage.read {
        val ids = interning.idsFor(this, filter)
        if (ids.matchesNothing) return@read emptyList()
        val holderIds = ids.holders

        val wanted = filter.limit + filter.offset
        val region = filter.region
        val batched = wanted >= BATCHED_FROM || region != null
        val scans: List<Scan>? = when {
            region != null -> regionScans(this, interning, region, filter.since, filter.until)
            holderIds.isNotEmpty() -> scansOver(holderIds.map { Tag.ACTOR[it] })
            else -> null
        }
        val matched = LongArrayList()
        val records = ArrayList<MemorySegment>()
        val take = { seq: Long ->
            val record = get(Tag.WCHG[seq])
            if (record != null && WorldFilter.accepts(this, interning, record, filter, ids)) {
                matched.add(seq)
                records += record
            }
            matched.size
        }

        if (batched) {
            val seqs = ascending(
                gatherSeqs(
                    LogKind.WORLD,
                    scans,
                    filter.since,
                    filter.until,
                    Int.MAX_VALUE,
                    filter.excludedCauses,
                    region
                ),
            )
            return@read loadSeqs(this, seqs, filter, ids, structureEnds = false)
        }

        if (scans != null) {
            if (scans.isEmpty()) return@read emptyList()
            mergeDescending(
                scans, LogKind.WORLD, wanted, take,
                sinceMillis = filter.since, untilMillis = filter.until, excludedCauses = filter.excludedCauses,
            )
        } else {
            scanDescendingByTime(LogKind.WORLD, filter.since, filter.until, wanted, take)
        }

        val from = filter.offset.coerceAtMost(matched.size)
        val until = (from.toLong() + filter.limit).coerceAtMost(matched.size.toLong()).toInt()
        val out = ArrayList<WorldChange>(until - from)
        for (i in from until until) decoder.expandInto(this, matched.getLong(i), records[i], null, filter, out)
        out
    }

    private fun loadSeqs(
        unit: StorageUnit,
        seqs: LongArray,
        filter: LookupFilter,
        ids: FilterIds,
        structureEnds: Boolean,
    ): List<WorldChange> {
        if (seqs.isEmpty()) return emptyList()
        val accepted = QueryProbe.phase("world records fetch") {
            pageAccepted(seqs, filter.offset, filter.limit) { slice, keep ->
                QueryProbe.opened(slice.size.toLong())
                val got = unit.fetchSlices(Tag.WCHG, slice) { WorldFilter.accepts(unit, interning, it, filter, ids) }
                for (i in 0 until got.seqs.size) keep(got.seqs.getLong(i), got.records[i])
            }
        }
        return pages.decodeAccepted(unit, accepted, filter, structureEnds)
    }

    private companion object {
        const val INITIAL_CAPACITY = 32
    }
}
