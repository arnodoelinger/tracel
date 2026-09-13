package com.tracel.storage.ports.log

import com.tracel.annotations.CauseKind
import com.tracel.annotations.isBookkeeping
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.LookupRegion
import com.tracel.engine.world.BlockEdit
import com.tracel.engine.world.BlockEdits
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.id.WorldId
import com.tracel.model.world.*
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.model.world.ActionKind
import com.tracel.model.log.LogKind
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.codec.records.SectionExtras
import com.tracel.storage.intern.Interning
import com.tracel.storage.util.ascending
import com.tracel.storage.util.eachIndex
import com.tracel.storage.util.pageNewest
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Appends a single change to the log and all its indexes. The log is append-only, so a change
     * at a sequence number that already exists is an error.
     */
    override suspend fun append(change: WorldChange) {
        storage.write {
            val seq = change.seq.raw
            check(get(Keys.wchg(seq)) == null) {
                "world change at ${change.seq} already appended — the log is append-only"
            }

            val causedById = change.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val worldId = interning.internWorld(this, change.at.world)
            val (_, x, y, z) = change.at
            val subject = change.subject

            var beforeDataId = -1
            var afterDataId = -1
            var inlineFlags = Records.INLINE_NEEDS_RECORD

            val record = when (subject) {
                is ChangeSubject.Block -> {
                    beforeDataId = interning.internBlockData(this, subject.before.data)
                    afterDataId = interning.internBlockData(this, subject.after.data)
                    if (subject.before.extras == null && subject.after.extras == null) inlineFlags = 0
                    Records.blockChange(
                        change.action,
                        change.cause,
                        causedById,
                        worldId,
                        x, y, z,
                        change.epochMillis,
                        beforeDataId,
                        afterDataId,
                        Records.blockExtras(subject.before.extras),
                        Records.blockExtras(subject.after.extras),
                    )
                }

                is ChangeSubject.Entity -> Records.entityChange(
                    change.action,
                    change.cause,
                    causedById,
                    worldId,
                    x, y, z,
                    change.epochMillis,
                    interning.internEntityType(this, subject.type),
                    subject.entity,
                    Records.entityShapePayload(subject.before),
                    Records.entityShapePayload(subject.after),
                )
            }

            put(Keys.wchg(seq), record)
            put(Keys.wchgAt(worldId, x, y, z, seq), NONE)
            if (subject is ChangeSubject.Entity) put(Keys.wchgEntity(subject.entity, seq), NONE)
            if (change.cause.isBookkeeping) return@write
            if (causedById != 0) put(Keys.actor(causedById, seq), OWN_LOG)
            put(Keys.time(change.epochMillis, seq), OWN_LOG)
            put(
                Keys.spatial(worldId, x shr 4, z shr 4, y, seq, change.epochMillis),
                Records.logKindInline(
                    LogKind.WORLD, change.cause, x, y, z,
                    change.action, beforeDataId, afterDataId, causedById, inlineFlags,
                ),
            )
        }
    }

    /**
     * Groups one event's edits by chunk section and writes the dense ones as a single record.
     *
     * A blast is one cause, one actor and one instant across thirty thousand blocks. Filing it a
     * block at a time wrote that same answer thirty thousand times over four families and left
     * thirty thousand rows in the spatial index for the next rollback to walk. Grouped, a section
     * is one record and one index row, and the blocks inside it cost about two bytes each.
     *
     * Sparse leftovers still go one at a time: below [SECTION_DELTA_FROM] a delta's fixed header
     * costs more than the records it would replace, and a player placing one block is not an
     * event worth a palette.
     */
    override suspend fun appendAll(edits: BlockEdits, nextSeqRange: suspend (Int) -> Seq): Int {
        val real = edits.edits.filter { it.before != it.after }
        if (real.isEmpty()) return 0

        val bySection = LinkedHashMap<Long, MutableList<BlockEdit>>()
        for (edit in real) {
            val at = edit.at
            bySection.getOrPut(sectionOf(at.x, at.y, at.z)) { ArrayList() } += edit
        }

        for (group in bySection.values) {
            if (group.size >= SECTION_DELTA_FROM) {
                appendSection(group, edits, nextSeqRange(group.size))
            } else {
                for ((at, before, after) in group) {
                    append(
                        WorldChange(
                            nextSeqRange(1), edits.action, edits.cause, edits.causedBy, edits.epochMillis,
                            at, ChangeSubject.Block(before, after),
                        )
                    )
                }
            }
        }
        return real.size
    }

    private suspend fun appendSection(group: List<BlockEdit>, edits: BlockEdits, base: Seq) {
        val ordered = group.sortedBy { Records.packSectionPosition(it.at.x, it.at.y, it.at.z) }
        val count = ordered.size
        val first = ordered.first().at
        val sectionX = first.x shr 4
        val sectionY = first.y shr 4
        val sectionZ = first.z shr 4

        storage.write {
            val baseSeq = base.raw
            check(get(Keys.wchg(baseSeq)) == null) {
                "world change at $base already appended — the log is append-only"
            }
            val causedById = edits.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val worldId = interning.internWorld(this, first.world)

            val positions = IntArray(count)
            val before = IntArray(count)
            val after = IntArray(count)
            val extras = ArrayList<SectionExtras>()
            for (i in 0 until count) {
                val edit = ordered[i]
                positions[i] = Records.packSectionPosition(edit.at.x, edit.at.y, edit.at.z)
                before[i] = interning.internBlockData(this, edit.before.data)
                after[i] = interning.internBlockData(this, edit.after.data)
                if (edit.before.extras != null || edit.after.extras != null) {
                    extras += SectionExtras(
                        i,
                        Records.blockExtras(edit.before.extras),
                        Records.blockExtras(edit.after.extras),
                    )
                }
            }

            put(
                Keys.wchg(baseSeq),
                Records.sectionDelta(
                    edits.action, edits.cause, causedById, worldId,
                    sectionX, sectionY, sectionZ, edits.epochMillis, baseSeq,
                    positions, count, before, after, extras,
                ),
            )
            put(Keys.wchgAtSection(worldId, sectionX, sectionY, sectionZ, baseSeq), NONE)
            if (edits.cause.isBookkeeping) return@write
            if (causedById != 0) put(Keys.actor(causedById, baseSeq), OWN_LOG)
            put(Keys.time(edits.epochMillis, baseSeq), OWN_LOG)
            put(
                Keys.spatial(worldId, sectionX, sectionZ, first.y, baseSeq, edits.epochMillis),
                Records.logKindSection(
                    LogKind.WORLD, edits.cause, edits.action,
                    sectionX shl 4, sectionY shl 4, sectionZ shl 4, count, causedById,
                ),
            )
        }
    }

    private fun sectionOf(x: Int, y: Int, z: Int): Long =
        ((x shr 4).toLong() and 0x1FFFFF shl 42) or
                ((z shr 4).toLong() and 0x1FFFFF shl 21) or
                ((y shr 4).toLong() and 0x1FFFFF)

    /**
     * Two families answer this: the per-coordinate one, and the per-section one that the deltas
     * write. Both come out newest first on their own, so the two are merged rather than sorted.
     *
     * A delta names a section, not a block, so each one found has to be asked whether it touched
     * this coordinate — a bit test — and only then unpacked.
     */
    override suspend fun at(pos: BlockPos, limit: Int): List<WorldChange> = storage.read {
        val worldId = interning.findWorldId(this, pos.world) ?: return@read emptyList()
        val single = ArrayList<WorldChange>(limit.coerceAtMost(INITIAL_CAPACITY))
        scan(Keys.wchgAtPrefix(worldId, pos.x, pos.y, pos.z)).use { cursor ->
            while (single.size < limit && cursor.next()) {
                val seq = Keys.invert(cursor.keyU64(17))
                get(Keys.wchg(seq))?.let { single += decode(this, seq, it) }
            }
        }

        val packed = Records.packSectionPosition(pos.x, pos.y, pos.z)
        val fromSections = ArrayList<WorldChange>()
        scan(Keys.wchgAtSectionPrefix(worldId, pos.x shr 4, pos.y shr 4, pos.z shr 4)).use { cursor ->
            while (fromSections.size < limit && cursor.next()) {
                val baseSeq = Keys.invert(cursor.keyU64(17))
                val record = get(Keys.wchg(baseSeq)) ?: continue
                val index = Records.sectionIndexOf(record, packed)
                if (index < 0) continue
                fromSections += sectionChangeAt(this, record, index, packed)
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

    private fun sectionChangeAt(
        unit: StorageUnit,
        record: MemorySegment,
        index: Int,
        packed: Int,
    ): WorldChange {
        val worldId = Records.wchgWorldId(record)
        val causedById = Records.wchgCausedBy(record)
        val extras = Records.sectionExtras(record, index)
        return WorldChange(
            Seq(Records.sectionBaseSeq(record) + index),
            Records.wchgAction(record),
            Records.wchgCause(record),
            if (causedById == 0) null else interning.resolveHolder(unit, causedById),
            Records.wchgEpochMillis(record),
            BlockPos(
                interning.resolveWorld(unit, worldId),
                Records.wchgX(record) + Records.sectionPositionX(packed),
                Records.wchgY(record) + Records.sectionPositionY(packed),
                Records.wchgZ(record) + Records.sectionPositionZ(packed),
            ),
            ChangeSubject.Block(
                sectionShape(unit, Records.sectionBefore(record, index), extras?.before, null),
                sectionShape(unit, Records.sectionAfter(record, index), extras?.after, null),
            ),
        )
    }

    /** Builds a shape from a section delta's palette index and optional extras. */
    @Suppress("UNCHECKED_CAST")
    override suspend fun queryTogether(
        transactions: com.tracel.engine.log.TransactionLog,
        filter: LookupFilter,
        includeWorld: Boolean,
        structureEnds: Boolean,
    ): Pair<List<WorldChange>, List<com.tracel.model.transaction.Transaction>> = storage.read {
        val ids = interning.idsFor(this, filter)
        if (ids.matchesNothing) return@read emptyList<WorldChange>() to emptyList()
        val holderIds = ids.holders
        val filterWorldId = ids.world
        val regionWorldId = ids.regionWorld
        val region = filter.region
        val scans: List<Scan>? = when {
            region != null -> regionScans(this, interning, region, filter.since, filter.until)
            holderIds.isNotEmpty() -> scansOver(holderIds.map(Keys::actorPrefix))
            else -> null
        }
        val inlineWorld = includeWorld && scans != null && region != null && regionWorldId != null &&
                (filterWorldId == null || filterWorldId == regionWorldId) &&
                filter.material == null && filter.offset == 0 && filter.limit == Int.MAX_VALUE
        val worldRows = if (inlineWorld) SpatialRows() else null

        val (worldSeqs, txnSeqs) = if (scans != null && scans.isEmpty()) {
            return@read emptyList<WorldChange>() to emptyList()
        } else {
            QueryProbe.phase("index") {
                gatherForFilter(scans, filter.since, filter.until, filter.excludedCauses, region, worldRows)
            }
        }
        val worldArr = if (inlineWorld) LongArray(0) else ascending(worldSeqs)
        val txnArr = ascending(txnSeqs)
        val unit = this
        val txnLog = transactions as TransactionLog
        val slot = arrayOfNulls<Any>(2)
        java.util.stream.IntStream.range(0, 2).parallel().forEach { i ->
            if (i == 0) {
                slot[0] = when {
                    !includeWorld -> emptyList()
                    worldRows != null -> QueryProbe.phase("world rows") {
                        fromRows(unit, worldRows, filter, ids, regionWorldId!!, structureEnds)
                    }

                    else -> QueryProbe.phase("world records") {
                        loadSeqs(unit, worldArr, filter, ids, structureEnds)
                    }
                }
            } else {
                val loaded = QueryProbe.phase("txn records") { txnLog.loadSeqs(unit, txnArr, filter) }
                val lots = QueryProbe.phase("txn lots") {
                    txnLog.loadLots(unit, LongArray(loaded.size) { loaded[it].seq.raw }.also(java.util.Arrays::sort))
                }
                slot[1] = if (lots.isEmpty()) loaded else loaded.map { txn ->
                    lots[txn.seq.raw]?.let { txn.copy(lots = it) } ?: txn
                }
            }
        }
        (slot[0] as List<WorldChange>) to (slot[1] as List<com.tracel.model.transaction.Transaction>)
    }

    /** The main query, which is the same as [queryTogether] but without the transaction side. */
    override suspend fun query(filter: LookupFilter): List<WorldChange> = storage.read {
        val ids = interning.idsFor(this, filter)
        if (ids.matchesNothing) return@read emptyList()
        val holderIds = ids.holders

        val wanted = filter.limit + filter.offset
        val region = filter.region
        val batched = wanted >= BATCHED_FROM || region != null
        val scans: List<Scan>? = when {
            region != null -> regionScans(this, interning, region, filter.since, filter.until)
            holderIds.isNotEmpty() -> scansOver(holderIds.map(Keys::actorPrefix))
            else -> null
        }
        val matched = ArrayList<Long>()
        val records = ArrayList<MemorySegment>()
        val take = { seq: Long ->
            val record = get(Keys.wchg(seq))
            if (record != null && accepts(record, filter, ids)) {
                matched += seq
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
        for (i in from until until) expandInto(this, matched[i], records[i], null, filter, out)
        out
    }

    private fun fromRows(
        unit: StorageUnit,
        rows: SpatialRows,
        filter: LookupFilter,
        ids: FilterIds,
        worldId: Int,
        structureEnds: Boolean,
    ): List<WorldChange> {
        val order = rows.ascendingOrder()
        val n = order.size
        val keep = BooleanArray(n)
        val dataIds = IntArray(n * 2)
        val holderIdsSeen = IntArray(n)
        var dataCount = 0
        var holderCount = 0
        var kept = 0
        var deferredCount = 0
        for (i in 0 until n) {
            val row = order[i]
            if (!acceptsRow(rows, row, filter, ids)) continue
            keep[i] = true
            kept++
            if (rows.deferred[row]) {
                deferredCount++
            } else {
                dataIds[dataCount++] = rows.beforeIds[row]
                dataIds[dataCount++] = rows.afterIds[row]
                if (rows.causedByIds[row] != 0) holderIdsSeen[holderCount++] = rows.causedByIds[row]
            }
        }
        if (kept == 0) return emptyList()
        QueryProbe.opened(deferredCount.toLong())
        QueryProbe.inlined((kept - deferredCount).toLong())

        val world = interning.resolveWorld(unit, worldId)
        val dataKeys = distinctOf(dataIds, dataCount)
        val shapes = arrayOfNulls<BlockShape>(dataKeys.size)
        for (i in dataKeys.indices) {
            val data = interning.resolveBlockData(unit, dataKeys[i])
            shapes[i] = if (data.value == BlockShape.AIR.data.value) BlockShape.AIR
            else simpleShapes.getOrPut(data) { BlockShape(data) }
        }
        val holderKeys = distinctOf(holderIdsSeen, holderCount)
        val holderValues = arrayOfNulls<HolderId>(holderKeys.size)
        for (i in holderKeys.indices) holderValues[i] = interning.resolveHolder(unit, holderKeys[i])

        // A row that needed its record can turn into any number of changes — a section delta is
        // one row standing for thousands of blocks — so a slot holds a list, not a change.
        val built = arrayOfNulls<WorldChange>(n)
        val expanded = arrayOfNulls<MutableList<WorldChange>>(n)
        val build = { i: Int ->
            if (keep[i]) {
                val row = order[i]
                if (rows.deferred[row]) {
                    val seq = rows.seqs[row]
                    unit.get(Keys.wchg(seq))?.let { record ->
                        // The index row said nothing about who did this or what action it was —
                        // that is why it deferred. Those questions belong to the record and have
                        // to be asked of it, or a rollback scoped to one player quietly takes in
                        // somebody else's explosion.
                        if (unit.accepts(record, filter, ids.copy(regionWorld = worldId, world = null))) {
                            val out = ArrayList<WorldChange>(1)
                            expandInto(unit, seq, record, null, filter, out)
                            expanded[i] = out
                        }
                    }
                } else {
                    val causedBy = rows.causedByIds[row]
                    built[i] = WorldChange(
                        Seq(rows.seqs[row]),
                        ActionKind.entries[rows.actions[row].toInt() and 0xFF],
                        CauseKind.entries[rows.causes[row].toInt() and 0xFF],
                        if (causedBy == 0) null else holderValues[indexOf(holderKeys, causedBy)],
                        rows.millis[row],
                        BlockPos(world, rows.xs[row], rows.ys[row], rows.zs[row]),
                        ChangeSubject.Block(
                            shapes[indexOf(dataKeys, rows.beforeIds[row])]!!,
                            shapes[indexOf(dataKeys, rows.afterIds[row])]!!,
                        ),
                    )
                }
            }
        }
        eachIndex(n, work = kept, body = build)

        val ordered = ArrayList<WorldChange>(kept)
        for (i in 0 until n) {
            built[i]?.let { ordered += it }
            expanded[i]?.let { ordered += it }
        }
        return if (structureEnds) structureEndsOf(ordered) else ordered.asReversed()
    }

    private fun distinctOf(ids: IntArray, count: Int): IntArray {
        if (count == 0) return IntArray(0)
        val sorted = ids.copyOf(count)
        java.util.Arrays.sort(sorted)
        var unique = 1
        for (i in 1 until count) if (sorted[i] != sorted[unique - 1]) sorted[unique++] = sorted[i]
        return if (unique == count) sorted else sorted.copyOf(unique)
    }

    private fun indexOf(keys: IntArray, id: Int): Int = java.util.Arrays.binarySearch(keys, id)

    private fun acceptsRow(rows: SpatialRows, row: Int, filter: LookupFilter, ids: FilterIds): Boolean {
        val holderIds = ids.holders
        val excludedIds = ids.excluded
        val cause = CauseKind.entries[rows.causes[row].toInt() and 0xFF]
        if (cause.isBookkeeping) return false
        if (filter.causes.isNotEmpty() && cause !in filter.causes) return false
        if (filter.excludedCauses.isNotEmpty() && cause in filter.excludedCauses) return false
        if (rows.deferred[row]) return true
        if (filter.actions.isNotEmpty() &&
            ActionKind.entries[rows.actions[row].toInt() and 0xFF] !in filter.actions
        ) {
            return false
        }
        val causedBy = rows.causedByIds[row]
        if (holderIds.isNotEmpty() && causedBy !in holderIds) return false
        if (excludedIds.isNotEmpty() && causedBy in excludedIds) return false
        return true
    }

    private fun structureEndsOf(ordered: List<WorldChange>): List<WorldChange> {
        val n = ordered.size
        // Both ends in one entry. Two maps meant hashing every coordinate twice on the way in and
        // walking two sets of values on the way out, for an answer one map already held.
        val ends = HashMap<Any, IntArray>(n.coerceAtMost(65_536))
        for (i in n - 1 downTo 0) {
            val slot = ends.getOrPut(ordered[i].structureKey()) { intArrayOf(i, i) }
            slot[1] = i
        }
        val pick = BooleanArray(n)
        for (slot in ends.values) {
            pick[slot[0]] = true
            pick[slot[1]] = true
        }
        val out = ArrayList<WorldChange>(ends.size * 2)
        for (i in n - 1 downTo 0) if (pick[i]) out += ordered[i]
        return out
    }


    private fun WorldChange.structureKey(): Any = when (val subject = subject) {
        is ChangeSubject.Block -> at
        is ChangeSubject.Entity -> subject.entity
    }

    private fun loadSeqs(
        unit: StorageUnit,
        seqs: LongArray,
        filter: LookupFilter,
        ids: FilterIds,
        structureEnds: Boolean,
    ): List<WorldChange> {
        val page = pageNewest(seqs, filter.offset, filter.limit)
        if (page.isEmpty()) return emptyList()
        QueryProbe.opened(page.size.toLong())
        val n = page.size
        val matched = ArrayList<Long>(n)
        val records = ArrayList<MemorySegment>(n)
        // Never walk the whole wchg family from min seq to max unconditionally
        QueryProbe.phase("world records fetch") {
            if (worthScanning(page)) {
                QueryProbe.scanned(n.toLong())
                unit.fetchAscending(Keys.WCHG, page, Keys::wchg) { seq, record ->
                    if (unit.accepts(record, filter, ids)) {
                        matched += seq
                        records += record
                    }
                }
            } else {
                QueryProbe.pointGot(n.toLong())
                val hits = arrayOfNulls<MemorySegment>(n)
                eachIndex(n) { i ->
                    val record = unit.get(Keys.wchg(page[i]))
                    if (record != null && unit.accepts(record, filter, ids)) hits[i] = record
                }
                for (i in 0 until n) {
                    val record = hits[i] ?: continue
                    matched += page[i]
                    records += record
                }
            }
        }
        return QueryProbe.phase("world records decode") {
            if (structureEnds) decodeStructureEnds(unit, matched, records, filter)
            else decodeNewestFirst(unit, matched, records, filter)
        }
    }

    private fun decodeNewestFirst(
        unit: StorageUnit,
        seqs: ArrayList<Long>,
        records: ArrayList<MemorySegment>,
        filter: LookupFilter,
    ): ArrayList<WorldChange> {
        val n = seqs.size
        if (n == 0) return ArrayList()
        val resolved = resolveAll(unit, records, pick = null)
        val slots = arrayOfNulls<MutableList<WorldChange>>(n)
        val build = { i: Int ->
            val one = ArrayList<WorldChange>(1)
            expandInto(unit, seqs[i], records[i], resolved, filter, one)
            slots[i] = one
        }
        eachIndex(n, body = build)
        val out = ArrayList<WorldChange>(n)
        for (i in n - 1 downTo 0) out += slots[i]!!
        return out
    }

    private fun decodeStructureEnds(
        unit: StorageUnit,
        seqs: ArrayList<Long>,
        records: ArrayList<MemorySegment>,
        filter: LookupFilter,
    ): ArrayList<WorldChange> {
        val n = seqs.size
        if (n == 0) return ArrayList()

        val ends = HashMap<Any, IntArray>(n.coerceAtMost(65_536))
        val pick = BooleanArray(n)
        var count = 0
        for (i in n - 1 downTo 0) {
            if (Records.wchgKind(records[i]) == Records.CHANGE_SECTION) {
                pick[i] = true
                count++
                continue
            }
            val slot = ends.getOrPut(structureKey(records[i])) { intArrayOf(i, i) }
            slot[1] = i
        }
        for (slot in ends.values) {
            if (!pick[slot[0]]) {
                pick[slot[0]] = true; count++
            }
            if (!pick[slot[1]]) {
                pick[slot[1]] = true; count++
            }
        }

        val resolved = resolveAll(unit, records, pick)
        val slots = arrayOfNulls<MutableList<WorldChange>>(n)
        val build = { i: Int ->
            if (pick[i]) {
                val one = ArrayList<WorldChange>(1)
                expandInto(unit, seqs[i], records[i], resolved, filter, one)
                slots[i] = one
            }
        }
        eachIndex(n, work = count, body = build)

        val ordered = ArrayList<WorldChange>(count)
        for (i in 0 until n) slots[i]?.let { ordered += it }
        return ArrayList(structureEndsOf(ordered))
    }

    private fun structureKey(record: MemorySegment): Any =
        if (Records.wchgKind(record) == Records.CHANGE_BLOCK) {
            BlockKey(
                Records.wchgWorldId(record),
                Records.wchgX(record),
                Records.wchgY(record),
                Records.wchgZ(record),
            )
        } else {
            Records.entityChangeUuid(record)
        }

    private data class BlockKey(val worldId: Int, val x: Int, val y: Int, val z: Int)

    private fun StorageUnit.accepts(record: MemorySegment, filter: LookupFilter, ids: FilterIds): Boolean {
        val holderIds = ids.holders
        val excludedIds = ids.excluded
        val regionWorldId = ids.regionWorld
        val filterWorldId = ids.world
        val unit = this
        val epochMillis = Records.wchgEpochMillis(record)
        val since = filter.since
        val until = filter.until
        if (since != null && epochMillis < since) return false
        if (until != null && epochMillis > until) return false
        val cause = Records.wchgCause(record)
        if (cause.isBookkeeping) return false
        if (filter.causes.isNotEmpty() && cause !in filter.causes) return false
        if (filter.excludedCauses.isNotEmpty() && cause in filter.excludedCauses) return false
        if (filter.actions.isNotEmpty() && Records.wchgAction(record) !in filter.actions) return false
        val causedBy = Records.wchgCausedBy(record)
        if (holderIds.isNotEmpty() && causedBy !in holderIds) return false
        if (excludedIds.isNotEmpty() && causedBy in excludedIds) return false

        val worldId = Records.wchgWorldId(record)
        if (filterWorldId != null && worldId != filterWorldId) return false
        val region = filter.region
        if (region != null && (regionWorldId == null || worldId != regionWorldId)) return false
        if (Records.wchgKind(record) == Records.CHANGE_SECTION) {
            return region == null || sectionMeets(record, region)
        }

        if (region != null &&
            !region.containsBlock(Records.wchgX(record), Records.wchgY(record), Records.wchgZ(record))
        ) {
            return false
        }

        val material = filter.material
        if (material != null) {
            if (Records.wchgKind(record) != Records.CHANGE_BLOCK) return false
            val before = interning.resolveBlockData(unit, Records.blockChangeBefore(record)).value
            val after = interning.resolveBlockData(unit, Records.blockChangeAfter(record)).value
            if (!before.materialEquals(material) && !after.materialEquals(material)) return false
        }
        return true
    }

    private fun sectionMeets(record: MemorySegment, region: LookupRegion): Boolean {
        val x = Records.wchgX(record)
        val y = Records.wchgY(record)
        val z = Records.wchgZ(record)
        if ((x shr 4) !in region.minChunkX..region.maxChunkX) return false
        if ((z shr 4) !in region.minChunkZ..region.maxChunkZ) return false
        return x + 15 >= region.minX && x <= region.maxX &&
                y + 15 >= region.minY && y <= region.maxY &&
                z + 15 >= region.minZ && z <= region.maxZ
    }

    private class Resolved(
        val worlds: Map<Int, WorldId>,
        val shapes: Map<Int, BlockShape>,
        val blockData: Map<Int, BlockDataKey>,
        val holders: Map<Int, HolderId>,
        val entityTypes: Map<Int, EntityTypeKey>,
    )

    private fun resolveAll(unit: StorageUnit, records: List<MemorySegment>, pick: BooleanArray?): Resolved {
        val worldIds = HashSet<Int>()
        val dataIds = HashSet<Int>()
        val holderIds = HashSet<Int>()
        val typeIds = HashSet<Int>()
        for (i in records.indices) {
            if (pick != null && !pick[i]) continue
            val record = records[i]
            worldIds += Records.wchgWorldId(record)
            val causedBy = Records.wchgCausedBy(record)
            if (causedBy != 0) holderIds += causedBy
            when (Records.wchgKind(record)) {
                Records.CHANGE_BLOCK -> {
                    dataIds += Records.blockChangeBefore(record)
                    dataIds += Records.blockChangeAfter(record)
                }

                Records.CHANGE_SECTION -> {
                    val palette = Records.sectionPaletteSize(record)
                    for (slot in 0 until palette) dataIds += Records.sectionPaletteAt(record, slot)
                }

                else -> typeIds += Records.entityChangeTypeId(record)
            }
        }

        val worlds = HashMap<Int, WorldId>(worldIds.size * 2)
        for (id in worldIds) worlds[id] = interning.resolveWorld(unit, id)
        val blockData = HashMap<Int, BlockDataKey>(dataIds.size * 2)
        val shapes = HashMap<Int, BlockShape>(dataIds.size * 2)
        for (id in dataIds) {
            val data = interning.resolveBlockData(unit, id)
            blockData[id] = data
            shapes[id] = if (data.value == BlockShape.AIR.data.value) BlockShape.AIR
            else simpleShapes.getOrPut(data) { BlockShape(data) }
        }
        val holders = HashMap<Int, HolderId>(holderIds.size * 2)
        for (id in holderIds) holders[id] = interning.resolveHolder(unit, id)
        val entityTypes = HashMap<Int, EntityTypeKey>(typeIds.size * 2)
        for (id in typeIds) entityTypes[id] = interning.resolveEntityType(unit, id)
        return Resolved(worlds, shapes, blockData, holders, entityTypes)
    }

    private fun expandInto(
        unit: StorageUnit,
        seq: Long,
        record: MemorySegment,
        resolved: Resolved?,
        filter: LookupFilter?,
        out: MutableList<WorldChange>,
    ) {
        if (Records.wchgKind(record) != Records.CHANGE_SECTION) {
            out += decode(unit, seq, record, resolved)
            return
        }

        val worldId = Records.wchgWorldId(record)
        val world = resolved?.worlds?.get(worldId) ?: interning.resolveWorld(unit, worldId)
        val cornerX = Records.wchgX(record)
        val cornerY = Records.wchgY(record)
        val cornerZ = Records.wchgZ(record)
        val action = Records.wchgAction(record)
        val cause = Records.wchgCause(record)
        val millis = Records.wchgEpochMillis(record)
        val causedById = Records.wchgCausedBy(record)
        val causedBy = when {
            causedById == 0 -> null
            else -> resolved?.holders?.get(causedById) ?: interning.resolveHolder(unit, causedById)
        }
        val baseSeq = Records.sectionBaseSeq(record)
        QueryProbe.expandedDelta(Records.sectionCount(record).toLong())
        val region = filter?.region
        val material = filter?.material

        Records.forEachSectionPosition(record) { index, packed ->
            val x = cornerX + Records.sectionPositionX(packed)
            val y = cornerY + Records.sectionPositionY(packed)
            val z = cornerZ + Records.sectionPositionZ(packed)
            if (region != null && !region.containsBlock(x, y, z)) return@forEachSectionPosition

            val beforeId = Records.sectionBefore(record, index)
            val afterId = Records.sectionAfter(record, index)
            if (material != null) {
                val before = interning.resolveBlockData(unit, beforeId).value
                val after = interning.resolveBlockData(unit, afterId).value
                if (!before.materialEquals(material) && !after.materialEquals(material)) {
                    return@forEachSectionPosition
                }
            }

            val extras = Records.sectionExtras(record, index)
            out += WorldChange(
                Seq(baseSeq + index),
                action,
                cause,
                causedBy,
                millis,
                BlockPos(world, x, y, z),
                ChangeSubject.Block(
                    sectionShape(unit, beforeId, extras?.before, resolved),
                    sectionShape(unit, afterId, extras?.after, resolved),
                ),
            )
        }
    }

    private fun sectionShape(
        unit: StorageUnit,
        dataId: Int,
        extras: ByteArray?,
        resolved: Resolved?,
    ): BlockShape {
        val data = resolved?.blockData?.get(dataId) ?: interning.resolveBlockData(unit, dataId)
        if (extras == null || extras.isEmpty()) {
            return if (data.value == BlockShape.AIR.data.value) BlockShape.AIR
            else simpleShapes.getOrPut(data) { BlockShape(data) }
        }
        return BlockShape(data, Records.decodeBlockExtras(extras))
    }

    private fun decode(unit: StorageUnit, seq: Long, record: MemorySegment, resolved: Resolved? = null): WorldChange {
        val version = Records.wchgVersion(record)
        check(version == Records.VERSION) {
            "world change at seq $seq was written by codec v$version, this build reads v${Records.VERSION}"
        }

        val worldId = Records.wchgWorldId(record)
        val at = BlockPos(
            resolved?.worlds?.get(worldId) ?: interning.resolveWorld(unit, worldId),
            Records.wchgX(record),
            Records.wchgY(record),
            Records.wchgZ(record),
        )

        val subject = when (val kind = Records.wchgKind(record)) {
            Records.CHANGE_BLOCK -> ChangeSubject.Block(
                blockShape(
                    unit,
                    Records.blockChangeBefore(record),
                    Records.blockChangeBeforeExtrasLength(record),
                    record,
                    before = true,
                    resolved = resolved
                ),
                blockShape(
                    unit,
                    Records.blockChangeAfter(record),
                    Records.blockChangeAfterExtrasLength(record),
                    record,
                    before = false,
                    resolved = resolved
                ),
            )

            Records.CHANGE_ENTITY -> {
                val action = Records.wchgAction(record)
                val typeId = Records.entityChangeTypeId(record)
                val type = resolved?.entityTypes?.get(typeId) ?: interning.resolveEntityType(unit, typeId)
                val beforeLen = Records.entityChangeBeforeExtrasLength(record)
                val afterLen = Records.entityChangeAfterExtrasLength(record)
                val beforePayload = Records.entityChangeBeforeExtras(record).takeIf { beforeLen > 0 } ?: ByteArray(0)
                val afterPayload = Records.entityChangeAfterExtras(record).takeIf { afterLen > 0 } ?: ByteArray(0)
                val before = Records.decodeEntityShape(type, at, beforePayload)
                    ?: if (action == ActionKind.ENTITY_SPAWN) null else EntityShape(
                        type,
                        at.x + 0.5,
                        at.y.toDouble(),
                        at.z + 0.5
                    )
                val after = Records.decodeEntityShape(type, at, afterPayload)
                    ?: if (action == ActionKind.ENTITY_REMOVE) null else EntityShape(
                        type,
                        at.x + 0.5,
                        at.y.toDouble(),
                        at.z + 0.5
                    )
                ChangeSubject.Entity(Records.entityChangeUuid(record), type, before, after)
            }

            else -> error("unrecognized world change kind: $kind")
        }

        val causedById = Records.wchgCausedBy(record)
        return WorldChange(
            Seq(seq),
            Records.wchgAction(record),
            Records.wchgCause(record),
            when {
                causedById == 0 -> null
                else -> resolved?.holders?.get(causedById) ?: interning.resolveHolder(unit, causedById)
            },
            Records.wchgEpochMillis(record),
            at,
            subject,
        )
    }

    private val simpleShapes = ConcurrentHashMap<BlockDataKey, BlockShape>()

    private fun blockShape(
        unit: StorageUnit,
        dataId: Int,
        extrasLen: Int,
        record: MemorySegment,
        before: Boolean,
        resolved: Resolved? = null,
    ): BlockShape {
        if (extrasLen == 0) resolved?.shapes?.get(dataId)?.let { return it }
        val data = resolved?.blockData?.get(dataId) ?: interning.resolveBlockData(unit, dataId)
        if (extrasLen == 0) {
            if (data.value == BlockShape.AIR.data.value) return BlockShape.AIR
            return simpleShapes.getOrPut(data) { BlockShape(data) }
        }
        val extras = Records.decodeBlockExtras(
            if (before) Records.blockChangeBeforeExtras(record) else Records.blockChangeAfterExtras(record),
        )
        return BlockShape(data, extras)
    }

    private companion object {
        val NONE = ByteArray(0)
        val OWN_LOG = Records.logKind(LogKind.WORLD)

        const val INITIAL_CAPACITY = 32
        const val SECTION_DELTA_FROM = 2

        fun String.materialEquals(material: String): Boolean {
            val stored = substringBefore('[')
            if (stored.equals(material, ignoreCase = true)) return true
            val bare = stored.substringAfter(':')
            val wanted = material.substringAfter(':')
            return bare.equals(wanted, ignoreCase = true)
        }
    }
}
