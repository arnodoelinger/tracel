package com.tracel.storage.chrono.diagenesis

import com.tracel.storage.Interning
import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.ChronoKeys
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Tile
import com.tracel.storage.codec.Tag

/**
 * Chrono's garbage collector (minus the garbage).
 *
 * New rows land as fragments, one per commit and tile. The collector weighs each waiting tile
 * ([Appraiser]) and silts the ones worth it ([Silting]); a slow sweep presses closed windows into
 * stratum, old ones into glacier and older into permafrost ([Lithify]).
 *
 * The game side may advise ([Hint]), but the bytes decide.
 */
internal class Diagenesis(
    private val settings: DiagenesisSettings,
    private val windowMillis: Long,
    capsuleRows: Int,
    interning: Interning,
) {
    private val ledger = Ledger()
    private val tally = Tally()
    private val appraiser = Appraiser(settings)
    private val filer = Filer(interning)
    private val silting = Silting(filer, settings, capsuleRows, ledger, tally)
    private val lithify = Lithify(filer, settings, windowMillis, capsuleRows, ledger, tally)
    private val retired = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var sealing: Set<Int> = emptySet()

    private var sweepWindow = Long.MIN_VALUE
    private var sweepFrom: ByteArray? = null
    private var sweepDone = true

    @Volatile
    private var wake = true

    /** A fragment of [bytes] has landed in [tile] on [lane]. */
    fun note(tile: Tile, lane: Lane, bytes: Int, now: Long) {
        tally.wrote(Generation.NEW, bytes.toLong())
        ledger.note(tile, lane, bytes, now)
    }

    /** A query read [tile]. */
    fun read(tile: Tile) = ledger.read(tile)

    /** A hint from the game side. */
    fun hint(hint: Hint, now: Long) {
        when (hint) {
            is Hint.Quiet -> ledger.urgent(hint.tile)
            is Hint.Burst -> for (tile in hint.tiles) ledger.hold(tile, now + settings.burstHoldMillis)
            is Hint.Pin -> ledger.pin(hint.tile, hint.pinned)
            is Hint.Retire -> {
                retired += hint.world
                wake = true
            }
        }
    }

    /** Diagenesis stats. */
    fun stats(): DiagenesisStats = DiagenesisStats(
        ledger.backlog(),
        tally.bytes(Generation.NEW),
        tally.runs(Generation.SILT), tally.bytes(Generation.SILT),
        tally.runs(Generation.STRATUM), tally.bytes(Generation.STRATUM),
        tally.runs(Generation.GLACIER), tally.bytes(Generation.GLACIER),
        tally.runs(Generation.PERMAFROST), tally.bytes(Generation.PERMAFROST),
    )

    /** Notes every fragment already on disk: whatever a crash or a shutdown left unmerged. */
    fun discover(unit: StorageUnit, now: Long) {
        unit.scan(Tag.CHRONO_FRAGMENT.prefix).use { cursor ->
            while (cursor.next()) {
                val tile = Tile.of(
                    Tag.CHRONO_FRAGMENT.int(cursor, 0),
                    Tag.CHRONO_FRAGMENT.int(cursor, 1),
                    Tag.CHRONO_FRAGMENT.int(cursor, 2),
                )
                ledger.note(tile, Lane.of(Tag.CHRONO_FRAGMENT.u8(cursor, 3)), cursor.value().byteSize().toInt(), now)
            }
        }
    }

    /**
     * The tiles to silt at [now], the best bargains first,
     * at most [limit]; all of them with [all].
     */
    fun due(now: Long, all: Boolean, limit: Int): List<Pair<Tile, Lane>> {
        ledger.fade()
        val scored = ArrayList<Triple<Double, Tile, Lane>>()
        for ((key, entry) in ledger.entries()) {
            val tile = Tile(key.first)
            val score = if (all) Double.MAX_VALUE else appraiser.score(entry, ledger.tail(key), ledger.reads(tile), now)
            if (score >= 1.0) scored += Triple(score, tile, key.second)
        }
        scored.sortByDescending { it.first }
        return scored.take(limit).map { it.second to it.third }
    }

    /** Silts [tile]'s fragments on [lane]. */
    fun silt(unit: StorageUnit, tile: Tile, lane: Lane): Int = silting.run(unit, tile, lane)

    /** Whether a sweep has anything left to look at at [now]. */
    fun sweeping(now: Long): Boolean = wake || !sweepDone || Math.floorDiv(now, windowMillis) != sweepWindow

    /**
     * Looks over the next [DiagenesisSettings.sweepGroups] tiles of the shelf and presses what is ripe.
     *
     * Starts over when a window closes or a world retires.
     */
    fun sweep(unit: StorageUnit, now: Long): Long {
        val current = Math.floorDiv(now, windowMillis)
        if (wake || current != sweepWindow) {
            wake = false
            sweepWindow = current
            sweepFrom = null
            sweepDone = false
            sealing = retired.toSet()
        }
        if (sweepDone) return 0L
        val groups = ArrayList<Pair<Tile, Lane>>()
        var last: Pair<Tile, Lane>? = null
        var counted = 0
        var seen = 0
        var exhausted = true
        val prefix = Tag.CHRONO_CAPSULE.prefix
        unit.scan(prefix, sweepFrom ?: prefix).use { cursor ->
            while (cursor.next()) {
                val tile = Tile.of(
                    Tag.CHRONO_CAPSULE.int(cursor, 0),
                    Tag.CHRONO_CAPSULE.int(cursor, 1),
                    Tag.CHRONO_CAPSULE.int(cursor, 2),
                )
                val lane = Lane.of(Tag.CHRONO_CAPSULE.u8(cursor, 3))
                val group = tile to lane
                if (group == last) {
                    counted++
                    if (counted == 2) groups += group
                    continue
                }
                if (last != null && ++seen >= settings.sweepGroups) {
                    exhausted = false
                    break
                }
                last = group
                counted = 1
            }
        }
        val resume = last
        if (exhausted) {
            sweepDone = true
            retired.removeAll(sealing)
            sealing = emptySet()
        } else if (resume != null) {
            sweepFrom = Tag.CHRONO_CAPSULE[resume.first.world, resume.first.x, resume.first.z, resume.second.id + 1]
        }
        var written = 0L
        for ((tile, lane) in groups) written += lithify.run(unit, tile, lane, now, tile.world in sealing)
        return written
    }
}
