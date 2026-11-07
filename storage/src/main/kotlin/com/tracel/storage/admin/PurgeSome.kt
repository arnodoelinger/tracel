package com.tracel.storage.admin

import com.tracel.engine.store.*
import com.tracel.model.holder.HolderId
import com.tracel.storage.TracelStorage
import com.tracel.storage.chrono.Chrono
import com.tracel.storage.chrono.ChronoKeys
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Tile
import com.tracel.storage.codec.History
import com.tracel.storage.codec.Tag
import com.tracel.storage.spi.HistorySegment
import com.tracel.storage.spi.SegmentEdit
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.foreign.MemorySegment

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

/** IDs of what the filter names, or [MISSING] if the store never heard of it, which matches nothing. */
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
    var seqs: LongOpenHashSet? = null
    var extra = 0L

    fun took(epoch: Long) {
        matched++
        if (epoch < oldest) oldest = epoch
        if (epoch > newest) newest = epoch
    }
}

private suspend fun purge(storage: TracelStorage, spec: PurgeSpec, apply: Boolean, around: Around): PurgeReport =
    Chrono.of(storage).pausingMerges { purgeHeld(storage, spec, apply, around) }

private suspend fun purgeHeld(storage: TracelStorage, spec: PurgeSpec, apply: Boolean, around: Around): PurgeReport {
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
            val analysis = analyze(storage, segment, wanted, everything = true)
            note(analysis)
            rows += analysis.matched + analysis.extra
            bytes += segment.bytes
        }
        if (apply && whole.isNotEmpty()) {
            around { withContext(Dispatchers.IO) { storage.engine.dropHistory(category.history, dropBefore!!) } }
        }

        for (segment in partial) {
            around {
                val analysis = analyze(storage, segment, wanted, everything = false)
                note(analysis)
                if (analysis.matched == 0L) return@around
                if (apply) {
                    val done = withContext(Dispatchers.IO) {
                        storage.engine.editSegment(segment.id) { key, value -> edit(analysis, key, value) }
                    }
                    rows += analysis.matched + analysis.extra
                    bytes += done.bytesFreed
                } else {
                    val removed = analysis.matched + analysis.extra
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

private fun edit(analysis: Analysis, key: ByteArray, value: MemorySegment?): SegmentEdit {
    val tag = Tag.of(key[0])?.takeIf { it == Tag.CHRONO_CAPSULE || it == Tag.CHRONO_FRAGMENT }
        ?: return if (doomed(analysis, key, value)) SegmentEdit.Drop else SegmentEdit.Keep
    val doomed = analysis.seqs ?: return SegmentEdit.Keep
    value ?: return SegmentEdit.Keep
    val lane = Lane.of(tag.u8(key, 3))
    val view = lane.open(value)
    val rows = view.rows()
    val keep = IntArray(rows.size)
    var kept = 0
    for (r in 0 until rows.size) {
        keep[kept] = r
        kept += if (rows.seq[r] in doomed) 0 else 1
    }
    return when (kept) {
        rows.size -> SegmentEdit.Keep
        0 -> SegmentEdit.Drop
        else -> SegmentEdit.Replace(lane.encode(rows.pick(keep, kept), view.window, view))
    }
}

private fun doomed(analysis: Analysis, key: ByteArray, value: MemorySegment?): Boolean {
    val seq = History.seqOf(key, value)
    return seq >= 0 && analysis.seqs?.contains(seq) == true
}

private suspend fun analyze(
    storage: TracelStorage,
    segment: HistorySegment,
    wanted: Wanted,
    everything: Boolean,
): Analysis = storage.read {
    val analysis = Analysis()
    if (!everything) analysis.seqs = LongOpenHashSet()
    for (tag in listOf(Tag.CHRONO_CAPSULE, Tag.CHRONO_FRAGMENT)) {
        storage.engine.readSegment(segment.id, tag.prefix) { cursor ->
            while (cursor.next()) {
                val key = cursor.key()
                val tile = ChronoKeys.tileOf(key, tag)
                val lane = Lane.of(tag.u8(key, 3))
                val rows = lane.open(cursor.value()).rows()
                for (r in 0 until rows.size) {
                    val counted = lane != Lane.ITEMS || (rows.aux!![1][r] == 0 && Tile.of(rows.aux[2][r], rows.aux[3][r], rows.aux[4][r]) == tile)
                    if (counted) analysis.total++
                    val world = if (lane == Lane.ITEMS) rows.aux!![2][r] else tile.world
                    val epoch = rows.millis[r]
                    if (everything || wanted.accepts(epoch, world, rows.actor[r])) {
                        if (counted) analysis.took(epoch)
                        analysis.seqs?.add(rows.seq[r])
                    }
                }
            }
        }
    }
    analysis
}
