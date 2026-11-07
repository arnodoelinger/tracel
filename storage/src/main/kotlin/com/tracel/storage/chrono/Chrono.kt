package com.tracel.storage.chrono

import com.tracel.platform.io.reason
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.chrono.query.ChronoReader
import com.tracel.storage.chrono.capsule.CapsuleView
import com.tracel.storage.codec.Tag
import com.tracel.storage.lsm.LsmConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds

/** How chrono files the world's history. */
data class ChronoSettings(
    val windowMillis: Long = LsmConfig.DAY_MILLIS,
    val mergeAfterMillis: Long = 30_000L,
    val mergeFragments: Int = 16,
    val passMillis: Long = 5_000L,
    val tilesPerPass: Int = 256,
    val capsuleRows: Int = Chrono.CAPSULE_ROWS,
    val slackMillis: Long = ChronoReader.SLACK_MILLIS,
    val clock: () -> Long = System::currentTimeMillis,
)

/**
 * The world's history, kept by place: every block and entity change,
 * in capsules of one tile for one window.
 */
class Chrono private constructor(private val storage: TracelStorage, val settings: ChronoSettings) {
    internal val merger = ChronoMerger(settings, storage.interning)
    private val logger = Logger.getLogger(Chrono::class.java.name)
    private val scope = CoroutineScope(
        SupervisorJob() + storage.dispatcher + CoroutineExceptionHandler { _, failure ->
            logger.log(Level.WARNING, "Chrono could not merge (${failure.reason()}).", failure)
        },
    )

    private val mergeLock = Mutex()

    /** The writer of [lane] for [unit]: one per unit, joined to its savepoints and its commit. */
    internal fun writer(unit: StorageUnit, lane: Lane): ChronoWriter =
        unit.participant(writerKeys[lane.id]) { ChronoWriter(lane, settings.windowMillis, merger::dirty) }

    /** A reader over what [unit]'s snapshot holds. */
    fun reader(unit: StorageUnit): ChronoReader = ChronoReader(StoredCapsules(unit), settings.slackMillis)

    /**
     * Whether [tile]'s history on [lane] already has sequence [seq].
     *
     * Walks newest first and stops at the first capsule that ends below it,
     * so a fresh sequence — every live append's — costs one look at the newest.
     */
    internal fun holds(unit: StorageUnit, tile: Tile, lane: Lane, seq: Long): Boolean {
        var held = false
        StoredCapsules(unit).tile(tile, lane) { view ->
            when {
                seq > view.lastSeq -> false
                seq >= view.firstSeq && view.seqs().binarySearch(seq) >= 0 -> {
                    held = true
                    false
                }
                else -> true
            }
        }
        return held
    }

    /**
     * Merges [tile]'s fragments on the next pass, however young:
     * nothing will change there for a while.
     */
    fun quiet(tile: Tile) {
        merger.urgent(tile)
    }

    /**
     * Runs [block] with the merger held off: a purge rewrites capsules,
     * and a merge in the middle would resurrect rows.
     */
    suspend fun <T> pausingMerges(block: suspend () -> T): T = mergeLock.withLock { block() }

    /**
     * Merges everything that waits, now.
     *
     * Shutdown, and tests that want capsules rather than fragments.
     */
    suspend fun mergeAll() {
        while (true) {
            val due = merger.due(settings.clock(), all = true, limit = settings.tilesPerPass)
            if (due.isEmpty()) return
            mergeLock.withLock { storage.write { for ((tile, lane) in due) merger.merge(this, tile, lane) } }
        }
    }

    /**
     * Reads the newest capsules of [tile] once,
     * so the blocks under them are in the cache before anyone asks.
     */
    suspend fun warm(tile: Tile) {
        storage.read {
            for (lane in Lane.entries) {
                scan(ChronoKeys.capsules(tile, lane)).use { cursor ->
                    var n = 0
                    while (n++ < WARM_CAPSULES && cursor.next()) lane.open(cursor.value()).cells
                }
            }
        }
    }

    /**
     * The wall clock of the first change at or after sequence [seq], or `null` with none:
     * where a history begins.
     *
     * Every capsule's time row says its first sequence and when; only one that straddles
     * [seq] is opened.
     */
    fun firstMillisFrom(unit: StorageUnit, seq: Long, lanes: List<Lane> = Lane.WORLD): Long? {
        var bestSeq = Long.MAX_VALUE
        var bestMillis: Long? = null
        fun offer(s: Long, m: Long) {
            if (s in seq..<bestSeq) {
                bestSeq = s
                bestMillis = m
            }
        }
        unit.scan(Tag.CHRONO_TIME.prefix).use { cursor ->
            while (cursor.next()) {
                val last = Tag.CHRONO_TIME.long(cursor, 0)
                if (last < seq) break
                val v = ByteBuffer.wrap(cursor.value().toArray(ValueLayout.JAVA_BYTE)).order(ByteOrder.LITTLE_ENDIAN)
                val first = v.getLong(1)
                val tile = Tile.of(Tag.CHRONO_TIME.int(cursor, 1), Tag.CHRONO_TIME.int(cursor, 2), Tag.CHRONO_TIME.int(cursor, 3))
                val lane = Lane.of(Tag.CHRONO_TIME.u8(cursor, 4))
                if (lane !in lanes) continue
                val stored = unit.get(ChronoKeys.capsule(tile, lane, last)) ?: continue
                if (first >= seq) {
                    offer(first, v.getLong(9))
                    continue
                }
                val view = lane.open(stored)
                val seqs = view.seqs()
                val millis = view.millis()
                for (i in seqs.indices) if (seqs[i] >= seq) { offer(seqs[i], millis[i]); break }
            }
        }
        for (lane in lanes) {
            for ((_, view) in StoredCapsules(unit).allFragments(lane)) {
                if (view.lastSeq < seq) continue
                val seqs = view.seqs()
                val millis = view.millis()
                for (i in seqs.indices) if (seqs[i] >= seq) { offer(seqs[i], millis[i]); break }
            }
        }
        return bestMillis
    }

    /** The capsules and fragments on [lane], anywhere, that hold any of [seqs]. */
    internal fun holding(unit: StorageUnit, lane: Lane, seqs: LongArray): List<Pair<Tile, CapsuleView>> {
        if (seqs.isEmpty()) return emptyList()
        val lowest = seqs.min()
        val highest = seqs.max()
        val sorted = seqs.sortedArray()
        fun holdsAny(first: Long, last: Long): Boolean {
            val at = sorted.binarySearch(first).let { if (it < 0) -it - 1 else it }
            return at < sorted.size && sorted[at] <= last
        }
        val out = ArrayList<Pair<Tile, CapsuleView>>()
        unit.scan(Tag.CHRONO_TIME.prefix).use { cursor ->
            while (cursor.next()) {
                val last = Tag.CHRONO_TIME.long(cursor, 0)
                if (last < lowest) break
                if (Tag.CHRONO_TIME.u8(cursor, 4) != lane.id) continue
                val first = ByteBuffer.wrap(cursor.value().toArray(ValueLayout.JAVA_BYTE)).order(ByteOrder.LITTLE_ENDIAN).getLong(1)
                if (first > highest || !holdsAny(first, last)) continue
                val tile = Tile.of(Tag.CHRONO_TIME.int(cursor, 1), Tag.CHRONO_TIME.int(cursor, 2), Tag.CHRONO_TIME.int(cursor, 3))
                out += tile to lane.open(unit.get(ChronoKeys.capsule(tile, lane, last)) ?: continue)
            }
        }
        for ((tile, view) in StoredCapsules(unit).allFragments(lane)) if (holdsAny(view.firstSeq, view.lastSeq)) out += tile to view
        return out
    }

    /** Where chrono stands. */
    fun stats(): ChronoStats = ChronoStats(merger.backlog(), merger.merges, merger.mergedBytes)

    private fun start() {
        StorageUnit.open(storage.engine).use(merger::discover)
        scope.launch {
            while (isActive) {
                delay(settings.passMillis.milliseconds)
                val due = merger.due(settings.clock(), all = false, limit = settings.tilesPerPass)
                if (due.isNotEmpty()) mergeLock.withLock { storage.write { for ((tile, lane) in due) merger.merge(this, tile, lane) } }
            }
        }
        storage.whenClosing { scope.cancel() }
    }

    companion object {
        private const val WARM_CAPSULES = 4
        private val writerKeys = Array(Lane.entries.size) { Any() }
        private val all = WeakHashMap<TracelStorage, Chrono>()

        const val CAPSULE_ROWS: Int = 16_384

        fun of(storage: TracelStorage, settings: ChronoSettings = ChronoSettings()): Chrono = synchronized(all) {
            all.getOrPut(storage) { Chrono(storage, settings).also { it.start() } }
        }
    }
}

/**
 * What chrono is doing: fragments waiting, merges done and the bytes they wrote,
 * since it started.
 */
data class ChronoStats(val waitingFragments: Int, val merges: Long, val mergedBytes: Long)
