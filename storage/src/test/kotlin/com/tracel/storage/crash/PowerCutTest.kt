package com.tracel.storage.crash

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.lsm.LsmOptions
import com.tracel.storage.lsm.wal.WalSyncPolicy
import com.tracel.storage.spi.MutationBatch
import com.tracel.storage.spi.WritesStopped
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.random.Random

class PowerCutTest {
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `every power cut leaves the batches that were promised, and only whole ones`() {
        val only = System.getenv("TRACEL_POWERCUT_SEED")?.toLong()
        val seeds = only?.let { listOf(it) } ?: (1L..(System.getenv("TRACEL_POWERCUT_SEEDS")?.toLong() ?: 64L)).toList()

        val logger = Logger.getLogger(LsmEngine::class.java.name)
        val level = logger.level
        logger.level = Level.OFF
        val reaper = Executors.newCachedThreadPool { Thread(it, "power-cut-reaper").apply { isDaemon = true } }
        val pool = Executors.newFixedThreadPool((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8))
        val tally = Tally()
        try {
            val runs = seeds.map { seed ->
                pool.submit<Throwable?> { runCatching { Machine(seed, reaper, tally).run() }.exceptionOrNull() }
            }
            val failures = runs.mapNotNull { it.get(10, TimeUnit.MINUTES) }
            println("power cut, ${seeds.size} seeds: $tally")
            if (failures.isNotEmpty()) {
                val first = failures.first()
                val all = failures.joinToString("\n") { "  " + it.message?.lineSequence()?.first() }
                val summary = "${failures.size} of ${seeds.size} seeds failed:\n$all\nFirst: ${first.message}"
                throw AssertionError(summary, first)
            }
        } finally {
            pool.shutdownNow()
            reaper.shutdown()
            reaper.awaitTermination(1, TimeUnit.MINUTES)
            logger.level = level
        }
    }

    private class Machine(private val seed: Long, private val reaper: ExecutorService, private val tally: Tally) {
        private val random = Random(seed)

        private val root = Path.of("/tracel-power-cut/$seed")

        private val config = LsmOptions(
            memtableBytes = random.nextLong(16L shl 10, 160L shl 10),
            maxFrozenMemtables = random.nextInt(1, 4),
            sync = when (random.nextInt(3)) {
                0 -> WalSyncPolicy.EveryBatch
                1 -> WalSyncPolicy.Interval(random.nextLong(1, 20))
                else -> WalSyncPolicy.Never
            },
            level0Fanout = random.nextInt(2, 5),
            deeperFanout = random.nextInt(2, 5),
            stateBlockBytes = 1 shl random.nextInt(9, 15),
            retryMillis = 2,
        )

        private val batches = ArrayList<Batch>()
        private var promised = 0
        private var image = PowerCutDisk.Image.EMPTY
        private var started = image
        private var life = 0

        fun run() {
            while (life < LIVES) {
                life++
                try {
                    live()
                } catch (e: Throwable) {
                    throw AssertionError(
                        "seed $seed, life $life of $LIVES (${describe()}): ${e.message}\n" +
                                "  the disk this life started from: ${started.note}",
                        e,
                    )
                }
            }
        }

        private fun describe() = with(config) {
            "memtable $memtableBytes, $maxFrozenMemtables frozen, $sync, fanout $level0Fanout / $deeperFanout, " +
                    "blocks $stateBlockBytes"
        }

        private fun live() {
            val cutAfter = when (random.nextInt(10)) {
                0, 1 -> Long.MAX_VALUE
                2, 3 -> random.nextLong(1, 40)
                else -> random.nextLong(1, 500)
            }
            val faultRate = if (random.nextInt(4) == 0) random.nextDouble(0.0005, 0.01) else 0.0
            val disk = PowerCutDisk(image, Random(random.nextLong()), cutAfter, faultRate)
            started = image

            tally.lives.incrementAndGet()
            val engine = try {
                LsmEngine(root, config, disk)
            } catch (e: Throwable) {
                if (!disk.dead && disk.faults == 0) throw AssertionError("refused to start: ${e.message}", e)
                tally.downAtStart.incrementAndGet()
                image = disk.cut()
                tally.count(disk)
                return
            }
            try {
                checkRecovered(engine)
                work(engine, disk)
            } finally {
                val stats = engine.stats()
                tally.flushes.addAndGet(stats.flushes)
                tally.compactions.addAndGet(stats.compactions)
                image = disk.cut()
                tally.count(disk)
                reaper.execute { engine.halt() }
            }
        }

        private fun checkRecovered(engine: LsmEngine) {
            val found = read(engine)
            val kept = found.marker
            if (kept < promised || kept > batches.size) {
                throw AssertionError(
                    "came back with $kept batches, but $promised were promised and ${batches.size} written."
                )
            }
            compare(stateAt(kept), found.rows, "after the cut, at batch $kept")
            tally.lostUnpromised.addAndGet((batches.size - kept).toLong())
            batches.subList(kept, batches.size).clear()
            promised = kept
        }

        private fun work(engine: LsmEngine, disk: PowerCutDisk) {
            repeat(random.nextInt(40, 400)) {
                if (disk.dead) return
                val roll = random.nextInt(100)
                try {
                    when {
                        roll < 84 -> write(engine)
                        roll < 88 -> {
                            engine.sync()
                            promised = batches.size
                        }

                        roll < 92 -> engine.flush()
                        roll < 94 -> engine.compactEverything()
                        roll < 96 -> if (!wipe(engine)) return
                        roll < 98 -> compare(stateAt(batches.size), read(engine).rows, "while running")
                        else -> {
                            val before = disk.faults
                            engine.close()
                            if (!disk.dead && disk.faults == before) promised = batches.size
                            return
                        }
                    }
                } catch (_: WritesStopped) {
                    if (disk.dead) return
                    Thread.sleep(random.nextLong(0, 4))
                }
            }
        }

        private fun write(engine: LsmEngine) {
            val rows = rows(deletes = true)
            val batch = MutationBatch()
            for ((id, value) in rows) if (value == null) batch.delete(key(id)) else batch.put(key(id), value.bytes())
            batch.put(MARKER, number(batches.size + 1))

            val durable = random.nextInt(4) != 0
            engine.write(batch, durable)
            tally.batches.incrementAndGet()
            batches += Batch(wipe = false, rows)
            if (durable && config.sync == WalSyncPolicy.EveryBatch && engine.stopped == null) promised = batches.size
        }

        /** A wipe that threw may or may not have happened, and leaves the engine shut: the life ends there. */
        private fun wipe(engine: LsmEngine): Boolean {
            val rows = rows(deletes = false)
            val keep = MutationBatch()
            for ((id, value) in rows) keep.put(key(id), value!!.bytes())
            keep.put(MARKER, number(batches.size + 1))
            batches += Batch(wipe = true, rows)
            return try {
                engine.wipe(keep)
                promised = batches.size
                true
            } catch (_: Throwable) {
                false
            }
        }

        private fun rows(deletes: Boolean): Map<Int, String?> {
            val rows = LinkedHashMap<Int, String?>()
            repeat(random.nextInt(1, 12)) {
                rows[random.nextInt(KEYS)] = if (deletes && random.nextInt(6) == 0) null else value()
            }
            return rows
        }

        private fun value(): String {
            val length = when (random.nextInt(20)) {
                0 -> random.nextInt(1500, 20_000)
                in 1..5 -> random.nextInt(64, 1500)
                else -> random.nextInt(0, 64)
            }
            val bytes = if (random.nextBoolean()) random.nextBytes(length)
            else ByteArray(length) { ('a' + random.nextInt(4)).code.toByte() }
            return String(bytes, Charsets.ISO_8859_1)
        }

        private fun stateAt(count: Int): Map<Int, String> {
            val state = TreeMap<Int, String>()
            for (batch in batches.subList(0, count)) {
                if (batch.wipe) state.clear()
                for ((id, value) in batch.rows) if (value == null) state.remove(id) else state[id] = value
            }
            return state
        }

        private fun compare(expected: Map<Int, String>, found: Map<Int, String>, where: String) {
            if (expected == found) return
            val keys = (expected.keys + found.keys).toSortedSet().filter { expected[it] != found[it] }
            val first = keys.first()
            throw AssertionError(
                "$where: ${keys.size} keys differ, first $first: expected ${origin(first, expected[first])}, " +
                        "found ${origin(first, found[first])}"
            )
        }

        private fun origin(id: Int, value: String?): String {
            val length = value?.let { "${it.length} bytes" } ?: "nothing"
            val batch = batches.indexOfLast { batch ->
                (batch.wipe && value == null && id !in batch.rows) || (id in batch.rows && batch.rows[id] == value)
            }
            return if (batch < 0) "$length, from no batch" else "$length, from batch ${batch + 1}"
        }

        private fun read(engine: LsmEngine): Found {
            val rows = TreeMap<Int, String>()
            var marker = 0
            engine.snapshot().use { snapshot ->
                snapshot.scan(ByteArray(0)).use { cursor ->
                    while (cursor.next()) {
                        val key = cursor.key()
                        val value = cursor.value().let { it.readBytes(0, it.byteSize().toInt()) }
                        if (key.contentEquals(MARKER)) marker = ByteBuffer.wrap(value).long.toInt()
                        else rows[ByteBuffer.wrap(key, 1, 4).int] = String(value, Charsets.ISO_8859_1)
                    }
                }
            }
            return Found(marker, rows)
        }
    }

    private class Batch(val wipe: Boolean, val rows: Map<Int, String?>)

    private class Tally {
        val lives = AtomicLong()
        val midway = AtomicLong()
        val downAtStart = AtomicLong()
        val faults = AtomicLong()
        val changes = AtomicLong()
        val batches = AtomicLong()
        val lostUnpromised = AtomicLong()
        val flushes = AtomicLong()
        val compactions = AtomicLong()

        fun count(disk: PowerCutDisk) {
            if (disk.midway) midway.incrementAndGet()
            faults.addAndGet(disk.faults.toLong())
            changes.addAndGet(disk.changes)
        }

        override fun toString() =
            "${lives.get()} lives, ${midway.get()} cut midway into a change, ${downAtStart.get()} of them while " +
                    "starting; ${changes.get()} disk changes, ${faults.get()} faults; ${batches.get()} batches, " +
                    "${lostUnpromised.get()} unpromised ones lost; ${flushes.get()} flushes, " +
                    "${compactions.get()} compactions"
    }

    private class Found(val marker: Int, val rows: Map<Int, String>)

    private companion object {
        const val LIVES = 10
        const val KEYS = 300

        val MARKER = byteArrayOf(0, 0, 0, 0, 0)

        fun key(id: Int): ByteArray = ByteBuffer.allocate(5).put(1).putInt(id).array()

        fun number(n: Int): ByteArray = ByteBuffer.allocate(8).putLong(n.toLong()).array()

        fun String.bytes(): ByteArray = toByteArray(Charsets.ISO_8859_1)
    }
}
