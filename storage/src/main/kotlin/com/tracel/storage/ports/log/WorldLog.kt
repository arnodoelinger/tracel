package com.tracel.storage.ports.log

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.world.edit.BlockEdit
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.log.LogKind
import com.tracel.model.log.Seq
import com.tracel.model.world.*
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.RawBlockEdits
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.codec.records.SectionExtras
import com.tracel.storage.intern.Interning
import com.tracel.storage.util.ascending
import com.tracel.storage.util.eachIndex
import com.tracel.storage.util.pageAccepted
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
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

    override suspend fun append(change: WorldChange) {
        storage.write {
            val seq = change.seq.raw
            check(get(Keys.wchg(seq)) == null) {
                "world change at ${change.seq} already appended; the log is append-only."
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
            if (causedById != 0) put(
                Keys.actor(causedById, seq),
                Records.logKindTimed(LogKind.WORLD, change.epochMillis, change.cause)
            )
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

    override suspend fun appendAll(edits: BlockEdits, nextSeqRange: suspend (Int) -> Seq): Int {
        val real = edits.edits.filter { it.before != it.after }
        if (real.isEmpty()) return 0

        val bySection = Long2ObjectLinkedOpenHashMap<MutableList<BlockEdit>>()
        for (edit in real) {
            val at = edit.at
            val section = sectionOf(at.x, at.y, at.z)
            var group = bySection.get(section)
            if (group == null) {
                group = ArrayList()
                bySection.put(section, group)
            }
            group += edit
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
                "world change at $base already appended; the log is append-only."
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
            if (causedById != 0) put(
                Keys.actor(causedById, baseSeq),
                Records.logKindTimed(LogKind.WORLD, edits.epochMillis, edits.cause)
            )
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

    /**
     * [appendAll] for edits that never left their interned IDs: no shape is resolved only to be interned again,
     * the whole event takes one range of sequences and lands in one write.
     */
    suspend fun appendRaw(raw: RawBlockEdits, nextSeqRange: suspend (Int) -> Seq): Int {
        val n = raw.count
        if (n == 0) return 0
        if (n >= RAW_MAX_EDITS) return appendAll(raw.resolved(), nextSeqRange)
        val coordinates = raw.coordinates

        val sectionIds = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
        val keys = LongArray(n)
        for (i in 0 until n) {
            val x = coordinates[i * 3]
            val y = coordinates[i * 3 + 1]
            val z = coordinates[i * 3 + 2]
            val sectionKey = sectionOf(x, y, z)
            var section = sectionIds.get(sectionKey)
            if (section < 0) {
                section = sectionIds.size
                sectionIds.put(sectionKey, section)
            }
            keys[i] = (section.toLong() shl 32) or (Records.packSectionPosition(x, y, z).toLong() shl 20) or i.toLong()
        }
        keys.sort()

        val base = nextSeqRange(n).raw
        val bookkeeping = raw.cause.isBookkeeping
        storage.write {
            check(get(Keys.wchg(base)) == null) {
                "world change at ${Seq(base)} already appended; the log is append-only."
            }
            val worldId = raw.worldId
            val causedById = raw.causedById
            val millis = raw.epochMillis
            val noExtras = Records.blockExtras(null)
            var seq = base
            var from = 0
            while (from < n) {
                val section = (keys[from] ushr 32).toInt()
                var until = from + 1
                while (until < n && (keys[until] ushr 32).toInt() == section) until++
                val count = until - from
                val first = (keys[from] and 0xFFFFF).toInt()
                val x0 = coordinates[first * 3]
                val y0 = coordinates[first * 3 + 1]
                val z0 = coordinates[first * 3 + 2]

                if (count >= SECTION_DELTA_FROM) {
                    val positions = IntArray(count)
                    val before = IntArray(count)
                    val after = IntArray(count)
                    for (k in 0 until count) {
                        val key = keys[from + k]
                        val i = (key and 0xFFFFF).toInt()
                        positions[k] = ((key ushr 20) and 0xFFF).toInt()
                        before[k] = raw.befores[i]
                        after[k] = raw.afters[i]
                    }
                    val sectionX = x0 shr 4
                    val sectionY = y0 shr 4
                    val sectionZ = z0 shr 4
                    put(
                        Keys.wchg(seq),
                        Records.sectionDelta(
                            raw.action, raw.cause, causedById, worldId,
                            sectionX, sectionY, sectionZ, millis, seq,
                            positions, count, before, after, emptyList(),
                        ),
                    )
                    put(Keys.wchgAtSection(worldId, sectionX, sectionY, sectionZ, seq), NONE)
                    if (!bookkeeping) {
                        if (causedById != 0) put(
                            Keys.actor(causedById, seq),
                            Records.logKindTimed(LogKind.WORLD, millis, raw.cause)
                        )
                        put(Keys.time(millis, seq), OWN_LOG)
                        put(
                            Keys.spatial(worldId, sectionX, sectionZ, y0, seq, millis),
                            Records.logKindSection(
                                LogKind.WORLD, raw.cause, raw.action,
                                sectionX shl 4, sectionY shl 4, sectionZ shl 4, count, causedById,
                            ),
                        )
                    }
                    seq += count
                } else {
                    for (k in from until until) { // TODO: parallelize?
                        val i = (keys[k] and 0xFFFFF).toInt()
                        val x = coordinates[i * 3]
                        val y = coordinates[i * 3 + 1]
                        val z = coordinates[i * 3 + 2]
                        put(
                            Keys.wchg(seq),
                            Records.blockChange(
                                raw.action, raw.cause, causedById, worldId, x, y, z, millis,
                                raw.befores[i], raw.afters[i], noExtras, noExtras,
                            ),
                        )
                        put(Keys.wchgAt(worldId, x, y, z, seq), NONE)
                        if (!bookkeeping) {
                            if (causedById != 0) put(
                                Keys.actor(causedById, seq),
                                Records.logKindTimed(LogKind.WORLD, millis, raw.cause)
                            )
                            put(Keys.time(millis, seq), OWN_LOG)
                            put(
                                Keys.spatial(worldId, x shr 4, z shr 4, y, seq, millis),
                                Records.logKindInline(
                                    LogKind.WORLD, raw.cause, x, y, z,
                                    raw.action, raw.befores[i], raw.afters[i], causedById, 0,
                                ),
                            )
                        }
                        seq++
                    }
                }
                from = until
            }
        }
        return n
    }

    private suspend fun RawBlockEdits.resolved(): BlockEdits = storage.read {
        val world = interning.resolveWorld(this, worldId)
        val edits = ArrayList<BlockEdit>(count)
        for (i in 0 until count) {
            edits += BlockEdit(
                BlockPos(world, coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2]),
                BlockShape(interning.resolveBlockData(this, befores[i])),
                BlockShape(interning.resolveBlockData(this, afters[i])),
            )
        }
        BlockEdits(
            action,
            cause,
            if (causedById == 0) null else interning.resolveHolder(this, causedById),
            epochMillis,
            edits
        )
    }

    private fun sectionOf(x: Int, y: Int, z: Int): Long =
        ((x shr 4).toLong() and 0x3FFFFF shl 42) or
                ((z shr 4).toLong() and 0x3FFFFF shl 20) or
                ((y shr 4).toLong() and 0xFFFFF)

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
                filter.material == null && filter.itemKeys.isEmpty() && filter.offset == 0 && filter.limit == Int.MAX_VALUE
        val worldRows = if (inlineWorld) SpatialRows() else null

        val (worldSeqs, txnSeqs) = if (scans != null && scans.isEmpty()) {
            return@read emptyList<WorldChange>() to emptyList()
        } else {
            QueryProbe.phase("index") {
                gatherForFilter(scans, filter.since, filter.until, filter.excludedCauses, region, worldRows)
            }
        }
        val worldArr = if (inlineWorld) LongArray(0) else ascending(worldSeqs)
        val txnLog = transactions as TransactionLog
        val byItem =
            if (scans == null) txnLog.itemKeyIdsFor(this, filter) else null
        val txnArr = if (byItem == null) ascending(txnSeqs) else ascending(
            QueryProbe.phase("item index") {
                gatherSeqs(
                    LogKind.TRANSACTION,
                    scansOver(byItem.map(Keys::itemPrefix)),
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
        val matched = LongArrayList()
        val records = ArrayList<MemorySegment>()
        val take = { seq: Long ->
            val record = get(Keys.wchg(seq))
            if (record != null && accepts(record, filter, ids)) {
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
        for (i in from until until) expandInto(this, matched.getLong(i), records[i], null, filter, out)
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
            shapes[i] = simpleShapes.getOrPut(data) { BlockShape(data) }
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
        val causes = filter.worldCauses ?: filter.causes
        if (causes.isNotEmpty() && cause !in causes) return false
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

    private fun structureEndsOf(ordered: List<WorldChange>): ArrayList<WorldChange> {
        val n = ordered.size
        packedEndsOf(ordered)?.let { return it }
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

    private fun packedEndsOf(ordered: List<WorldChange>): ArrayList<WorldChange>? {
        val n = ordered.size
        if (n < PACKED_ENDS_FROM) return null
        val world = ordered[0].at.world
        var entityRows = 0
        for (i in 0 until n) {
            val change = ordered[i]
            if (change.subject is ChangeSubject.Entity) entityRows++
            else if (change.at.world != world) return null
        }
        val blocks = EndsTable(n - entityRows, wide = false)
        val entities = if (entityRows == 0) null else EndsTable(entityRows, wide = true)
        for (i in n - 1 downTo 0) {
            val change = ordered[i]
            val subject = change.subject
            if (subject is ChangeSubject.Entity) {
                entities!!.put(i, subject.entity.mostSignificantBits, subject.entity.leastSignificantBits)
                continue
            }
            val at = change.at
            blocks.put(
                i,
                ((at.x.toLong() and 0x3FFFFFF) shl 38) or ((at.z.toLong() and 0x3FFFFFF) shl 12) or
                        (at.y.toLong() and 0xFFF),
                0L,
            )
        }
        val pick = BooleanArray(n)
        val count = blocks.mark(pick) + (entities?.mark(pick) ?: 0)
        val out = ArrayList<WorldChange>(count)
        for (i in n - 1 downTo 0) if (pick[i]) out += ordered[i]
        return out
    }

    private class EndsTable(expected: Int, wide: Boolean) {
        private val shift: Int
        private val mask: Int
        private val keys: LongArray
        private val keys2: LongArray?
        private val firsts: IntArray
        private val lasts: IntArray

        init {
            val capacity = Integer.highestOneBit(((expected.toLong() * 3 / 2).coerceIn(16, 1L shl 30)).toInt() - 1) shl 1
            shift = 64 - Integer.numberOfTrailingZeros(capacity)
            mask = capacity - 1
            keys = LongArray(capacity)
            keys2 = if (wide) LongArray(capacity) else null
            firsts = IntArray(capacity)
            lasts = IntArray(capacity)
        }

        fun put(index: Int, key: Long, key2: Long) {
            var h = (((key xor (key2 * -0x4b47d4a8a3b9d5e3L)) * -0x61c8864680b583ebL) ushr shift).toInt()
            while (true) {
                val first = firsts[h]
                if (first == 0) {
                    keys[h] = key
                    if (keys2 != null) keys2[h] = key2
                    firsts[h] = index + 1
                    break
                }
                if (keys[h] == key && (keys2 == null || keys2[h] == key2)) break
                h = (h + 1) and mask
            }
            lasts[h] = index + 1
        }

        fun mark(pick: BooleanArray): Int {
            var count = 0
            for (h in firsts.indices) {
                val first = firsts[h] - 1
                if (first < 0) continue
                if (!pick[first]) {
                    pick[first] = true
                    count++
                }
                val last = lasts[h] - 1
                if (!pick[last]) {
                    pick[last] = true
                    count++
                }
            }
            return count
        }
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
        if (seqs.isEmpty()) return emptyList()
        val accepted = QueryProbe.phase("world records fetch.") {
            pageAccepted(seqs, filter.offset, filter.limit) { slice, keep ->
                QueryProbe.opened(slice.size.toLong())
                val n = slice.size
                if (worthScanning(slice)) {
                    QueryProbe.scanned(n.toLong())
                    unit.fetchAscending(Keys.WCHG, slice, Keys::wchg) { seq, record ->
                        if (unit.accepts(record, filter, ids)) keep(seq, record)
                    }
                } else {
                    QueryProbe.pointGot(n.toLong())
                    val hits = arrayOfNulls<MemorySegment>(n)
                    eachIndex(n) { i ->
                        val record = unit.get(Keys.wchg(slice[i]))
                        if (record != null && unit.accepts(record, filter, ids)) hits[i] = record
                    }
                    for (i in 0 until n) keep(slice[i], hits[i] ?: continue)
                }
            }
        }
        val matched = accepted.seqs
        val records = accepted.records
        return QueryProbe.phase("world records decode.") {
            if (structureEnds) decodeStructureEnds(unit, matched, records, filter)
            else decodeNewestFirst(unit, matched, records, filter)
        }
    }

    private fun decodeNewestFirst(
        unit: StorageUnit,
        seqs: LongArrayList,
        records: ArrayList<MemorySegment>,
        filter: LookupFilter,
    ): ArrayList<WorldChange> {
        val n = seqs.size
        if (n == 0) return ArrayList()
        val resolved = resolveAll(unit, records, pick = null)
        val slots = arrayOfNulls<Any>(n)
        val build = { i: Int -> slots[i] = expandOne(unit, seqs.getLong(i), records[i], resolved, filter) }
        eachIndex(n, body = build)
        val out = ArrayList<WorldChange>(n)
        for (i in n - 1 downTo 0) out.addExpanded(slots[i])
        return out
    }

    private fun decodeStructureEnds(
        unit: StorageUnit,
        seqs: LongArrayList,
        records: ArrayList<MemorySegment>,
        filter: LookupFilter,
    ): ArrayList<WorldChange> {
        val n = seqs.size
        if (n == 0) return ArrayList()

        var blockRows = 0
        var entityRows = 0
        for (i in 0 until n) {
            when (Records.wchgKind(records[i])) {
                Records.CHANGE_BLOCK -> blockRows++
                Records.CHANGE_SECTION -> Unit
                else -> entityRows++
            }
        }
        val blocks = EndsTable(blockRows, wide = true)
        val entities = EndsTable(entityRows, wide = true)
        val pick = BooleanArray(n)
        var count = 0
        for (i in n - 1 downTo 0) {
            val record = records[i]
            when (Records.wchgKind(record)) {
                Records.CHANGE_SECTION -> {
                    pick[i] = true
                    count++
                }

                Records.CHANGE_BLOCK -> blocks.put(
                    i,
                    (Records.wchgX(record).toLong() shl 32) or (Records.wchgZ(record).toLong() and 0xFFFFFFFFL),
                    (Records.wchgY(record).toLong() shl 32) or (Records.wchgWorldId(record).toLong() and 0xFFFFFFFFL),
                )

                else -> entities.put(i, Records.entityChangeUuidHigh(record), Records.entityChangeUuidLow(record))
            }
        }
        count += blocks.mark(pick) + entities.mark(pick)

        val resolved = resolveAll(unit, records, pick)
        val slots = arrayOfNulls<Any>(n)
        val build = { i: Int ->
            if (pick[i]) slots[i] = expandOne(unit, seqs.getLong(i), records[i], resolved, filter)
        }
        eachIndex(n, work = count, body = build)

        val ordered = ArrayList<WorldChange>(count)
        for (i in 0 until n) ordered.addExpanded(slots[i])
        return structureEndsOf(ordered)
    }

    private fun expandOne(
        unit: StorageUnit,
        seq: Long,
        record: MemorySegment,
        resolved: Resolved?,
        filter: LookupFilter?,
    ): Any = if (Records.wchgKind(record) != Records.CHANGE_SECTION) {
        decode(unit, seq, record, resolved)
    } else {
        ArrayList<WorldChange>().also { expandInto(unit, seq, record, resolved, filter, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ArrayList<WorldChange>.addExpanded(slot: Any?) {
        when (slot) {
            null -> Unit
            is WorldChange -> add(slot)
            else -> addAll(slot as List<WorldChange>)
        }
    }

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
        val causes = filter.worldCauses ?: filter.causes
        if (causes.isNotEmpty() && cause !in causes) return false
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
            if (region != null && !sectionMeets(record, region)) return false
            val material = filter.material ?: return true
            for (slot in 0 until Records.sectionPaletteSize(record)) {
                val data = interning.resolveBlockData(unit, Records.sectionPaletteAt(record, slot)).value
                if (data.matchesAny(material, filter.blockMaterials)) return true
            }
            return false
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
            if (!before.matchesAny(material, filter.blockMaterials) && !after.matchesAny(
                    material,
                    filter.blockMaterials
                )
            ) return false
        }
        return true
    }

    private fun sectionMeets(record: MemorySegment, region: LookupRegion): Boolean {
        val x = Records.wchgX(record)
        val y = Records.wchgY(record)
        val z = Records.wchgZ(record)
        if ((x shr 4) !in region.minTileX..region.maxTileX) return false
        if ((z shr 4) !in region.minTileZ..region.maxTileZ) return false
        return x + 15 >= region.minX && x <= region.maxX &&
                y + 15 >= region.minY && y <= region.maxY &&
                z + 15 >= region.minZ && z <= region.maxZ
    }

    private class Resolved(
        val worlds: Int2ObjectMap<WorldId>,
        val shapes: Int2ObjectMap<BlockShape>,
        val blockData: Int2ObjectMap<BlockDataKey>,
        val holders: Int2ObjectMap<HolderId>,
        val entityTypes: Int2ObjectMap<EntityTypeKey>,
    )

    private fun resolveAll(unit: StorageUnit, records: List<MemorySegment>, pick: BooleanArray?): Resolved {
        val worldIds = IntOpenHashSet()
        val dataIds = IntOpenHashSet()
        val holderIds = IntOpenHashSet()
        val typeIds = IntOpenHashSet()
        for (i in records.indices) {
            if (pick != null && !pick[i]) continue
            val record = records[i]
            worldIds.add(Records.wchgWorldId(record))
            val causedBy = Records.wchgCausedBy(record)
            if (causedBy != 0) holderIds.add(causedBy)
            when (Records.wchgKind(record)) {
                Records.CHANGE_BLOCK -> {
                    dataIds.add(Records.blockChangeBefore(record))
                    dataIds.add(Records.blockChangeAfter(record))
                }

                Records.CHANGE_SECTION -> {
                    val palette = Records.sectionPaletteSize(record)
                    for (slot in 0 until palette) dataIds.add(Records.sectionPaletteAt(record, slot))
                }

                else -> typeIds.add(Records.entityChangeTypeId(record))
            }
        }

        val worlds = Int2ObjectOpenHashMap<WorldId>(worldIds.size)
        for (id in worldIds.toIntArray()) worlds.put(id, interning.resolveWorld(unit, id))
        val blockData = Int2ObjectOpenHashMap<BlockDataKey>(dataIds.size)
        val shapes = Int2ObjectOpenHashMap<BlockShape>(dataIds.size)
        for (id in dataIds.toIntArray()) {
            val data = interning.resolveBlockData(unit, id)
            blockData.put(id, data)
            shapes.put(id, simpleShapes.getOrPut(data) { BlockShape(data) })
        }
        val holders = Int2ObjectOpenHashMap<HolderId>(holderIds.size)
        for (id in holderIds.toIntArray()) holders.put(id, interning.resolveHolder(unit, id))
        val entityTypes = Int2ObjectOpenHashMap<EntityTypeKey>(typeIds.size)
        for (id in typeIds.toIntArray()) entityTypes.put(id, interning.resolveEntityType(unit, id))
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

        // Palette resolved once per delta, slots read in one go: per block only the position is left to decode
        val palette = Records.sectionPalette(record)
        val shapes = Array(palette.size) { sectionShape(unit, palette[it], null, resolved) }
        val wanted = if (material == null) null else {
            val aliases = filter.blockMaterials
            BooleanArray(palette.size) { shapes[it].data.value.matchesAny(material, aliases) }
        }
        val slots = Records.sectionSlots(record)
        val count = slots.size / 2
        val extrasAll = Records.sectionExtrasAll(record)
        if (out is ArrayList<WorldChange>) out.ensureCapacity(out.size + count)

        Records.forEachSectionPosition(record) { index, packed ->
            val x = cornerX + Records.sectionPositionX(packed)
            val y = cornerY + Records.sectionPositionY(packed)
            val z = cornerZ + Records.sectionPositionZ(packed)
            if (region != null && !region.containsBlock(x, y, z)) return@forEachSectionPosition

            val beforeSlot = slots[index]
            val afterSlot = slots[count + index]
            if (wanted != null && !wanted[beforeSlot] && !wanted[afterSlot]) return@forEachSectionPosition

            val extras = extrasAll?.get(index)
            out += WorldChange(
                Seq(baseSeq + index),
                action,
                cause,
                causedBy,
                millis,
                BlockPos(world, x, y, z),
                ChangeSubject.Block(
                    if (extras == null) shapes[beforeSlot] else sectionShape(
                        unit,
                        palette[beforeSlot],
                        extras.before,
                        resolved
                    ),
                    if (extras == null) shapes[afterSlot] else sectionShape(
                        unit,
                        palette[afterSlot],
                        extras.after,
                        resolved
                    ),
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
            return simpleShapes.getOrPut(data) { BlockShape(data) }
        }
        return BlockShape(data, Records.decodeBlockExtras(extras))
    }

    private fun decode(unit: StorageUnit, seq: Long, record: MemorySegment, resolved: Resolved? = null): WorldChange {
        val version = Records.wchgVersion(record)
        check(version == Records.VERSION) {
            "world change at seq $seq was written by codec v$version, this build reads v${Records.VERSION}."
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
                val before = Records.entityChangeBeforeShape(type, at, record)
                    ?: if (action == ActionKind.ENTITY_SPAWN) null else EntityShape(
                        type,
                        at.x + 0.5,
                        at.y.toDouble(),
                        at.z + 0.5
                    )
                val after = Records.entityChangeAfterShape(type, at, record)
                    ?: if (action == ActionKind.ENTITY_REMOVE) null else EntityShape(
                        type,
                        at.x + 0.5,
                        at.y.toDouble(),
                        at.z + 0.5
                    )
                ChangeSubject.Entity(Records.entityChangeUuid(record), type, before, after)
            }

            else -> error("unrecognized world change kind: $kind.")
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
            return simpleShapes.getOrPut(data) { BlockShape(data) }
        }
        val extras =
            if (before) Records.blockChangeBeforeBlockExtras(record) else Records.blockChangeAfterBlockExtras(record)
        return BlockShape(data, extras)
    }

    private companion object {
        val NONE = ByteArray(0)
        val OWN_LOG = Records.logKind(LogKind.WORLD)

        const val INITIAL_CAPACITY = 32
        const val SECTION_DELTA_FROM = 2
        const val RAW_MAX_EDITS = 1 shl 20
        const val PACKED_ENDS_FROM = 1024

        fun String.matchesAny(material: String, aliases: Set<String>): Boolean =
            materialEquals(material) || aliases.any { materialEquals(it) }

        fun String.materialEquals(material: String): Boolean {
            if ('[' in material) return equals(material, ignoreCase = true)
            val stored = substringBefore('[')
            if (stored.equals(material, ignoreCase = true)) return true
            val bare = stored.substringAfter(':')
            val wanted = material.substringAfter(':')
            return bare.equals(wanted, ignoreCase = true)
        }
    }
}
