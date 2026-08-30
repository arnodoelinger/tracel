package com.tracel.storage.ports

import com.tracel.annotations.isBookkeeping
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.world.WorldLog as WorldLogPort
import com.tracel.model.id.Seq
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockDataKey
import com.tracel.model.world.BlockPos
import com.tracel.model.world.BlockShape
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.EntityShape
import com.tracel.model.world.LogKind
import com.tracel.model.world.WorldChange
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.intern.Interning
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

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
                "world change at ${change.seq} already appended — the log is append-only"
            }

            val causedById = change.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val worldId = interning.internWorld(this, change.at.world)
            val (_, x, y, z) = change.at
            val subject = change.subject

            val record = when (subject) {
                is ChangeSubject.Block -> Records.blockChange(
                    change.action,
                    change.cause,
                    causedById,
                    worldId,
                    x, y, z,
                    change.epochMillis,
                    interning.internBlockData(this, subject.before.data),
                    interning.internBlockData(this, subject.after.data),
                    Records.blockExtras(subject.before.extras),
                    Records.blockExtras(subject.after.extras),
                )

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
            // Restore is not lookup history. The shared spatial/time/actor families are what a
            // region rollback walks; writing them here made the next query re-read the last one.
            if (change.cause.isBookkeeping) return@write
            if (causedById != 0) put(Keys.actor(causedById, seq), OWN_LOG)
            put(Keys.time(change.epochMillis, seq), OWN_LOG)
            put(
                Keys.spatial(worldId, x shr 4, z shr 4, y, seq, change.epochMillis),
                Records.logKind(LogKind.WORLD, change.epochMillis, change.cause, x, y, z),
            )
        }
    }

    override suspend fun at(pos: BlockPos, limit: Int): List<WorldChange> = storage.read {
        val worldId = interning.findWorldId(this, pos.world) ?: return@read emptyList()
        val out = ArrayList<WorldChange>(limit.coerceAtMost(INITIAL_CAPACITY))
        scan(Keys.wchgAtPrefix(worldId, pos.x, pos.y, pos.z)).use { cursor ->
            while (out.size < limit && cursor.next()) {
                val seq = Keys.invert(KeyReader.u64(cursor.key(), 17))
                get(Keys.wchg(seq))?.let { out += decode(this, seq, it) }
            }
        }
        out
    }

    override suspend fun queryTogether(
        transactions: com.tracel.engine.log.TransactionLog,
        filter: LookupFilter,
    ): Pair<List<WorldChange>, List<com.tracel.model.transaction.Transaction>> = storage.read {
        val holderIds = filter.holders.mapNotNull { interning.findHolderId(this, it) }.toSet()
        if (filter.holders.isNotEmpty() && holderIds.isEmpty()) {
            return@read emptyList<WorldChange>() to emptyList()
        }
        val excludedIds = filter.excludedHolders.mapNotNull { interning.findHolderId(this, it) }.toSet()
        val filterWorldId = filter.world?.let { interning.findWorldId(this, it) }
        if (filter.world != null && filterWorldId == null) {
            return@read emptyList<WorldChange>() to emptyList()
        }
        val region = filter.region
        val regionWorldId = region?.let { interning.findWorldId(this, it.world) }
        val scans: List<Scan>? = when {
            region != null -> regionScans(this, interning, region, filter.since, filter.until)
            holderIds.isNotEmpty() -> scansOver(holderIds.map(Keys::actorPrefix))
            else -> null
        }
        val (worldSeqs, txnSeqs) = if (scans != null && scans.isEmpty()) {
            return@read emptyList<WorldChange>() to emptyList()
        } else {
            QueryProbe.phase("index") {
                gatherForFilter(scans, filter.since, filter.until, filter.excludedCauses, region)
            }
        }
        val worldArr = ascending(worldSeqs)
        val txnArr = ascending(txnSeqs)
        val unit = this
        val txnLog = transactions as TransactionLog
        // Record fetch and decode for the two families share nothing; doing them in single file
        // paid for the smaller one after the larger one was already done.
        val slot = arrayOfNulls<Any>(2)
        java.util.stream.IntStream.range(0, 2).parallel().forEach { i ->
            if (i == 0) {
                slot[0] = QueryProbe.phase("world records") {
                    loadSeqs(unit, worldArr, filter, holderIds, excludedIds, regionWorldId, filterWorldId)
                }
            } else {
                val loaded = QueryProbe.phase("txn records") { txnLog.loadSeqs(unit, txnArr, filter) }
                // Linkage only for the transactions that survived the filter. Asking for it up
                // front meant a prefix cursor per gathered sequence, most of them for rows the
                // filter was about to throw away.
                val lots = QueryProbe.phase("txn lots") {
                    txnLog.loadLots(unit, LongArray(loaded.size) { loaded[it].seq.raw }.also(java.util.Arrays::sort))
                }
                slot[1] = if (lots.isEmpty()) loaded else loaded.map { txn ->
                    lots[txn.seq.raw]?.let { txn.copy(lots = it) } ?: txn
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        (slot[0] as List<WorldChange>) to (slot[1] as List<com.tracel.model.transaction.Transaction>)
    }

    override suspend fun query(filter: LookupFilter): List<WorldChange> = storage.read {
        val holderIds = filter.holders.mapNotNull { interning.findHolderId(this, it) }.toSet()
        if (filter.holders.isNotEmpty() && holderIds.isEmpty()) return@read emptyList()
        val excludedIds = filter.excludedHolders.mapNotNull { interning.findHolderId(this, it) }.toSet()
        val filterWorldId = filter.world?.let { interning.findWorldId(this, it) }
        if (filter.world != null && filterWorldId == null) return@read emptyList()

        val wanted = filter.limit + filter.offset
        val region = filter.region
        val batched = wanted >= BATCHED_FROM || region != null
        val regionWorldId = region?.let { interning.findWorldId(this, it.world) }
        val scans: List<Scan>? = when {
            region != null -> regionScans(this, interning, region, filter.since, filter.until)
            holderIds.isNotEmpty() -> scansOver(holderIds.map(Keys::actorPrefix))
            else -> null
        }
        val matched = ArrayList<Long>()
        val records = ArrayList<MemorySegment>()
        val take = { seq: Long ->
            val record = get(Keys.wchg(seq))
            if (record != null && accepts(record, filter, holderIds, excludedIds, regionWorldId, filterWorldId)) {
                matched += seq
                records += record
            }
            matched.size
        }

        if (batched) {
            val seqs = ascending(
                gatherSeqs(LogKind.WORLD, scans, filter.since, filter.until, Int.MAX_VALUE, filter.excludedCauses, region),
            )
            return@read loadSeqs(this, seqs, filter, holderIds, excludedIds, regionWorldId, filterWorldId)
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
        for (i in from until until) out += decode(this, matched[i], records[i])
        out
    }

    private fun loadSeqs(
        unit: StorageUnit,
        seqs: LongArray,
        filter: LookupFilter,
        holderIds: Set<Int>,
        excludedIds: Set<Int>,
        regionWorldId: Int?,
        filterWorldId: Int?,
    ): List<WorldChange> {
        val page = pageNewest(seqs, filter.offset, filter.limit)
        if (page.isEmpty()) return emptyList()
        QueryProbe.opened(page.size.toLong())
        val matched = ArrayList<Long>(page.size)
        val records = ArrayList<MemorySegment>(page.size)
        if (worthScanning(page)) {
            unit.fetchAscending(Keys.WCHG, page, Keys::wchg) { seq, record ->
                if (unit.accepts(record, filter, holderIds, excludedIds, regionWorldId, filterWorldId)) {
                    matched += seq
                    records += record
                }
            }
            return decodeNewestFirst(unit, matched, records)
        }
        return collectNewestFirst(page) { seq ->
            val record = unit.get(Keys.wchg(seq)) ?: return@collectNewestFirst null
            if (!unit.accepts(
                    record,
                    filter,
                    holderIds,
                    excludedIds,
                    regionWorldId,
                    filterWorldId
                )
            ) return@collectNewestFirst null
            decode(unit, seq, record)
        }
    }

    private fun decodeNewestFirst(
        unit: StorageUnit,
        seqs: ArrayList<Long>,
        records: ArrayList<MemorySegment>,
    ): ArrayList<WorldChange> {
        val n = seqs.size
        if (n == 0) return ArrayList()
        val slots = arrayOfNulls<WorldChange>(n)
        if (n >= PARALLEL_GETS_FROM) {
            java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
                slots[i] = decode(unit, seqs[i], records[i])
            }
        } else {
            for (i in 0 until n) slots[i] = decode(unit, seqs[i], records[i])
        }
        val out = ArrayList<WorldChange>(n)
        for (i in n - 1 downTo 0) out += slots[i]!!
        return out
    }

    private fun StorageUnit.accepts(
        record: MemorySegment,
        filter: LookupFilter,
        holderIds: Set<Int>,
        excludedIds: Set<Int>,
        regionWorldId: Int?,
        filterWorldId: Int?,
    ): Boolean {
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
        if (region != null) {
            if (regionWorldId == null || worldId != regionWorldId) return false
            if (!region.containsBlock(Records.wchgX(record), Records.wchgY(record), Records.wchgZ(record))) return false
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

    private fun decode(unit: StorageUnit, seq: Long, record: MemorySegment): WorldChange {
        val version = Records.wchgVersion(record)
        check(version == Records.VERSION) {
            "world change at seq $seq was written by codec v$version, this build reads v${Records.VERSION}"
        }

        val worldId = Records.wchgWorldId(record)
        val at = BlockPos(
            interning.resolveWorld(unit, worldId),
            Records.wchgX(record),
            Records.wchgY(record),
            Records.wchgZ(record),
        )

        val subject = when (val kind = Records.wchgKind(record)) {
            Records.CHANGE_BLOCK -> ChangeSubject.Block(
                blockShape(unit, Records.blockChangeBefore(record), Records.blockChangeBeforeExtrasLength(record), record, before = true),
                blockShape(unit, Records.blockChangeAfter(record), Records.blockChangeAfterExtrasLength(record), record, before = false),
            )

            Records.CHANGE_ENTITY -> {
                val action = Records.wchgAction(record)
                val type = interning.resolveEntityType(unit, Records.entityChangeTypeId(record))
                val beforeLen = Records.entityChangeBeforeExtrasLength(record)
                val afterLen = Records.entityChangeAfterExtrasLength(record)
                val beforePayload = Records.entityChangeBeforeExtras(record).takeIf { beforeLen > 0 } ?: ByteArray(0)
                val afterPayload = Records.entityChangeAfterExtras(record).takeIf { afterLen > 0 } ?: ByteArray(0)
                val before = Records.decodeEntityShape(type, at, beforePayload)
                    ?: if (action == ActionKind.ENTITY_SPAWN) null else EntityShape(type, at.x + 0.5, at.y.toDouble(), at.z + 0.5)
                val after = Records.decodeEntityShape(type, at, afterPayload)
                    ?: if (action == ActionKind.ENTITY_REMOVE) null else EntityShape(type, at.x + 0.5, at.y.toDouble(), at.z + 0.5)
                ChangeSubject.Entity(Records.entityChangeUuid(record), type, before, after)
            }

            else -> error("unrecognized world change kind: $kind")
        }

        val causedById = Records.wchgCausedBy(record)
        return WorldChange(
            Seq(seq),
            Records.wchgAction(record),
            Records.wchgCause(record),
            if (causedById == 0) null else interning.resolveHolder(unit, causedById),
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
    ): BlockShape {
        val data = interning.resolveBlockData(unit, dataId)
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

        fun String.materialEquals(material: String): Boolean {
            val stored = substringBefore('[')
            if (stored.equals(material, ignoreCase = true)) return true
            val bare = stored.substringAfter(':')
            val wanted = material.substringAfter(':')
            return bare.equals(wanted, ignoreCase = true)
        }
    }
}
