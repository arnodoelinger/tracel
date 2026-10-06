package com.tracel.storage.ports.ops

import com.tracel.engine.store.*
import com.tracel.model.holder.HolderId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.History
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.codec.records.ContainerSlot
import com.tracel.storage.ports.event.EventLog
import com.tracel.storage.spi.HistorySegment
import com.tracel.storage.util.LongSetUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer

/** Runs [it] immediately, without waiting for anything. */
private val IMMEDIATELY: Around = { it() }

/** The kind of history in the store that [this] category covers. */
internal val PurgeCategory.history: Int
    get() = when (this) {
        PurgeCategory.BLOCKS -> History.BLOCKS
        PurgeCategory.ITEMS -> History.ITEMS
        PurgeCategory.CONTAINERS -> History.CONTAINERS
        PurgeCategory.EVENTS -> History.EVENTS
    }

/** Counts what a purge of [spec] would take, and touches nothing. */
suspend fun previewPurge(storage: TracelStorage, spec: PurgeSpec): PurgeReport =
    purge(storage, spec, apply = false, IMMEDIATELY)

/**
 * Takes what [spec] says out of the history.
 *
 * A window of history that is wholly in what is asked for, which is every old enough window when only the age is, is
 * thrown away as a file: nothing is read or rewritten, and the space is back at once. A window that is only partly in
 * it is rewritten without those rows, which costs the space of that one window for a moment and no more.
 *
 * Every piece goes through [around], where a caller makes it wait out whatever must not run beside it.
 */
suspend fun purgeSome(storage: TracelStorage, spec: PurgeSpec, around: Around = IMMEDIATELY): PurgeReport =
    purge(storage, spec, apply = true, around)

/** Ids of what the filter names, or [MISSING] if the store never heard of it, which matches nothing. */
private class Wanted(val before: Long?, val world: Int?, val player: Int?) {
    fun accepts(epoch: Long, worldId: Int, causedBy: Int): Boolean =
        (before == null || epoch < before) &&
                (world == null || world == worldId) &&
                (player == null || player == causedBy)

    companion object {
        const val MISSING = -1
    }
}

private class Analysis {
    var total = 0L
    var matched = 0L
    var oldest = Long.MAX_VALUE
    var newest = Long.MIN_VALUE
    var seqs: LongSetUtils? = null
    var keys: HashSet<ByteBuffer>? = null

    fun took(epoch: Long) {
        matched++
        if (epoch < oldest) oldest = epoch
        if (epoch > newest) newest = epoch
    }
}

private suspend fun purge(storage: TracelStorage, spec: PurgeSpec, apply: Boolean, around: Around): PurgeReport {
    withContext(Dispatchers.IO) { storage.engine.flush() }
    val filter = spec.filter
    val wanted = storage.read {
        Wanted(
            filter.before,
            filter.world?.let { storage.interning.findWorldId(this, it) ?: Wanted.MISSING },
            filter.player?.let { storage.interning.findHolderId(this, HolderId.Player(it)) ?: Wanted.MISSING },
        )
    }
    val dropBefore = if (filter.world == null && filter.player == null) filter.before ?: Long.MAX_VALUE else null

    val tallies = LinkedHashMap<PurgeCategory, PurgeTally>()
    for (category in PurgeCategory.entries) {
        if (category !in spec.categories) continue
        val segments = storage.engine.history(category.history)
        val whole = segments.filter { dropBefore != null && it.windowEndMillis <= dropBefore }
        val partial = if (spec.wholeWindowsOnly) emptyList() else segments - whole.toSet()

        var total = 0L
        var matched = 0L
        var rows = 0L
        var bytes = 0L
        var oldest = Long.MAX_VALUE
        var newest = Long.MIN_VALUE
        fun note(analysis: Analysis) {
            total += analysis.total
            matched += analysis.matched
            if (analysis.oldest < oldest) oldest = analysis.oldest
            if (analysis.newest > newest) newest = analysis.newest
        }

        for (segment in whole) {
            note(analyze(storage, category, segment, wanted, filter, everything = true))
            rows += segment.entries
            bytes += segment.bytes
        }
        if (apply && whole.isNotEmpty()) {
            around { withContext(Dispatchers.IO) { storage.engine.dropHistory(category.history, dropBefore!!) } }
        }

        for (segment in partial) {
            around {
                val analysis = analyze(storage, category, segment, wanted, filter, everything = false)
                note(analysis)
                if (analysis.matched == 0L) return@around
                if (apply) {
                    val done = withContext(Dispatchers.IO) {
                        storage.engine.rewriteSegment(segment.id) { key, value ->
                            !doomed(
                                analysis,
                                category,
                                key,
                                value
                            )
                        }
                    }
                    rows += done.rowsRemoved
                    bytes += done.bytesFreed
                } else {
                    val removed = countRows(storage, category, segment, analysis)
                    rows += removed
                    bytes += if (segment.entries == 0L) 0L else segment.bytes * removed / segment.entries
                }
            }
        }
        tallies[category] = PurgeTally(
            total, matched, rows,
            oldest.takeIf { it != Long.MAX_VALUE },
            newest.takeIf { it != Long.MIN_VALUE },
            bytes,
        )
    }
    return PurgeReport(tallies)
}

private fun doomed(analysis: Analysis, category: PurgeCategory, key: ByteArray, value: MemorySegment?): Boolean {
    if (category == PurgeCategory.CONTAINERS) return analysis.keys?.contains(ByteBuffer.wrap(key)) == true
    val seq = History.seqOf(key, value)
    return seq >= 0 && analysis.seqs?.contains(seq) == true
}

private suspend fun countRows(
    storage: TracelStorage,
    category: PurgeCategory,
    segment: HistorySegment,
    analysis: Analysis
): Long =
    storage.read {
        storage.engine.readSegment(segment.id, ByteArray(0)) { cursor ->
            var rows = 0L
            while (cursor.next()) if (doomed(analysis, category, cursor.key(), cursor.value())) rows++
            rows
        } ?: 0L
    }

private suspend fun analyze(
    storage: TracelStorage,
    category: PurgeCategory,
    segment: HistorySegment,
    wanted: Wanted,
    filter: PurgeFilter,
    everything: Boolean,
): Analysis = storage.read {
    val analysis = Analysis()
    if (!everything && category != PurgeCategory.CONTAINERS) analysis.seqs = LongSetUtils()
    when (category) {
        PurgeCategory.BLOCKS -> storage.engine.readSegment(segment.id, Keys.tagPrefix(Keys.WCHG)) { cursor ->
            while (cursor.next()) {
                val v = cursor.value()
                analysis.total++
                val epoch = Records.wchgEpochMillis(v)
                if (everything || wanted.accepts(epoch, Records.wchgWorldId(v), Records.wchgCausedBy(v))) {
                    analysis.took(epoch)
                    analysis.seqs?.add(KeyReader.u64(cursor.key(), 1))
                }
            }
        }

        PurgeCategory.ITEMS -> storage.engine.readSegment(segment.id, Keys.tagPrefix(Keys.TXN)) { cursor ->
            while (cursor.next()) {
                val v = cursor.value()
                analysis.total++
                val epoch = Records.txnEpochMillis(v)
                if (everything || wanted.accepts(epoch, Records.txnWorldId(v), Records.txnCausedBy(v))) {
                    analysis.took(epoch)
                    analysis.seqs?.add(KeyReader.u64(cursor.key(), 1))
                }
            }
        }

        PurgeCategory.EVENTS -> storage.engine.readSegment(segment.id, Keys.tagPrefix(Keys.EVENT)) { cursor ->
            while (cursor.next()) {
                val v = cursor.value()
                analysis.total++
                val epoch = EventLog.epochMillis(v)
                if (everything || wanted.accepts(epoch, EventLog.worldId(v), EventLog.byId(v))) {
                    analysis.took(epoch)
                    analysis.seqs?.add(KeyReader.u64(cursor.key(), 1))
                }
            }
        }

        PurgeCategory.CONTAINERS -> analyzeContainers(storage, segment, filter, everything, analysis)
    }
    analysis
}

private fun StorageUnit.analyzeContainers(
    storage: TracelStorage,
    segment: HistorySegment,
    filter: PurgeFilter,
    everything: Boolean,
    analysis: Analysis,
) {
    val keys = HashSet<ByteBuffer>()
    val sitsIn = HashMap<Int, Boolean>()
    val before = filter.before
    storage.engine.readSegment(segment.id, Keys.tagPrefix(Keys.CONTAINER_SLOT)) { cursor ->
        while (cursor.next()) {
            analysis.total++
            val epoch = ContainerSlot.layoutEpochMillis(cursor.value())
            val holderId = cursor.keyU32(1)
            val taken = everything || (
                    (before == null || epoch < before) &&
                            (filter.world == null || sitsIn.getOrPut(holderId) {
                                val holder = runCatching { storage.interning.resolveHolder(this, holderId) }.getOrNull()
                                (holder as? HolderId.Block)?.world == filter.world
                            })
                    )
            if (taken) {
                analysis.took(epoch)
                keys += ByteBuffer.wrap(cursor.key())
            }
        }
    }
    storage.engine.readSegment(segment.id, Keys.tagPrefix(Keys.ACTOR_VISIT)) { cursor ->
        while (cursor.next()) {
            analysis.total++
            val epoch = Keys.invert(cursor.keyU64(5))
            if (everything || (filter.world == null && (before == null || epoch < before))) {
                analysis.took(epoch)
                keys += ByteBuffer.wrap(cursor.key())
            }
        }
    }
    analysis.keys = keys
}
