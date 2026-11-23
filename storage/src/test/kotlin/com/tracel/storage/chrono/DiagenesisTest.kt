package com.tracel.storage.chrono

import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.chrono.capsule.LithCapsule
import com.tracel.storage.chrono.lith.Burst
import com.tracel.storage.chrono.lith.Bursts
import com.tracel.storage.chrono.lith.Voids
import com.tracel.storage.chrono.diagenesis.DiagenesisSettings
import com.tracel.storage.chrono.diagenesis.Hint
import com.tracel.storage.codec.History
import com.tracel.storage.codec.Tag
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.lsm.LsmOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random

class DiagenesisTest {
    private val day = 86_400_000L
    private val start = 1_700_000_000_000L - 1_700_000_000_000L % day

    private class Rig(val storage: TracelStorage, val chrono: Chrono, val clock: () -> Long) {
        val oracle = HashMap<Pair<Tile, Int>, Int>()
        var seq = 1_000_000L
        private val random = Random(3)

        fun commit(tile: Tile, at: Long, n: Int = 1) = runBlocking {
            storage.write {
                val writer = chrono.writer(this, Lane.BLOCKS)
                repeat(n) {
                    seq += 1 + random.nextInt(0, 3)
                    val cell = random.nextInt(0, 16) or (64 shl 8) or (random.nextInt(0, 16) shl 4)
                    writer.add(tile, seq, at, cell, 41, 0, random.nextInt(0, 12), random.nextInt(0, 12), null, null)
                    oracle.merge(tile to cell, 1, Int::plus)
                }
            }
        }

        fun pass() = runBlocking { chrono.pass() }

        fun count(tag: Tag): Int = StorageUnit.open(storage.engine).use { unit ->
            unit.scan(tag.prefix).use { c -> var n = 0; while (c.next()) n++; n }
        }

        fun capsulesOf(tile: Tile): Int = StorageUnit.open(storage.engine).use { unit ->
            unit.scan(ChronoKeys.capsules(tile, Lane.BLOCKS)).use { c -> var n = 0; while (c.next()) n++; n }
        }

        fun coldOf(tile: Tile): Int = StorageUnit.open(storage.engine).use { unit ->
            unit.scan(ChronoKeys.capsules(tile, Lane.BLOCKS)).use { c ->
                var n = 0
                while (c.next()) if (Lane.BLOCKS.open(c.value()) is LithCapsule) n++
                n
            }
        }

        fun assertAnswers(label: String) = runBlocking {
            storage.read {
                val reader = chrono.reader(this)
                for ((key, want) in oracle) {
                    assertEquals(want, reader.history(key.first, Cell(key.second)).size, "$label: ${key.first} ${Cell(key.second)}")
                }
            }
        }
    }

    private fun rig(
        dir: Path,
        diagenesis: DiagenesisSettings = DiagenesisSettings(),
        engine: (clock: () -> Long) -> LsmOptions = { LsmOptions() },
        block: Rig.(setClock: (Long) -> Unit) -> Unit,
    ) {
        var now = start
        TracelStorage.open(dir) { LsmEngine(it, History.configured(engine { now })) }.use { storage ->
            val chrono = Chrono.of(
                storage,
                ChronoSettings(passMillis = 1_000_000_000_000L, clock = { now }, diagenesis = diagenesis),
            )
            Rig(storage, chrono) { now }.block { now = it }
        }
    }

    @Test
    fun `settling presses today as if it were over`(@TempDir dir: Path) = rig(dir) { _ ->
        val tile = Tile.of(1, 0, 0)
        repeat(40) { commit(tile, start + 1_000L * it, 25) }
        pass()
        assertEquals(0, coldOf(tile), "the day is still open")
        runBlocking { chrono.settle() }
        assertEquals(capsulesOf(tile), coldOf(tile), "every capsule of the open day is cold now")
        assertTrue(count(Tag.CHRONO_FRAGMENT) == 0, "nothing waits as a fragment")
    }

    @Test
    fun `a closed window is pressed into one capsule and answers the same`(@TempDir dir: Path) = rig(dir) { clock ->
        val tile = Tile.of(1, 3, 4)
        for (i in 0 until 400) {
            val at = start + i * 60_000L
            clock(at)
            commit(tile, at, 1 + i % 3)
            if (i % 5 == 0) pass()
        }
        clock(start + 2 * day)
        repeat(6) { pass() }
        assertEquals(0, count(Tag.CHRONO_FRAGMENT))
        assertEquals(1, capsulesOf(tile), "400 commits of one window fit one capsule once it is closed")
        assertEquals(1, coldOf(tile), "a closed window is lithified")
        assertAnswers("stratum")
    }

    @Test
    fun `old windows freeze into one capsule per span`(@TempDir dir: Path) = rig(dir) { clock ->
        val tile = Tile.of(1, 0, 0)
        for (d in 0 until 21) for (i in 0 until 6) {
            val at = start + d * day + i * 3_600_000L
            clock(at)
            commit(tile, at, 3)
            pass()
        }
        clock(start + 60 * day)
        repeat(4) { pass() }
        assertAnswers("glacier")
        assertTrue(capsulesOf(tile) <= 4, "21 days are three spans, got ${capsulesOf(tile)} capsules")
        assertTrue(chrono.stats().diagenesis.glacierRuns > 0)
    }

    @Test
    fun `a merge survives the engine compacting the window it was read from`(@TempDir dir: Path) =
        rig(dir, engine = { clock -> LsmOptions(clock = clock) }) { clock ->
            val tile = Tile.of(1, 7, 7)
            val lsm = storage.engine as LsmEngine
            clock(start)
            commit(tile, start, 3)
            lsm.flushNow()
            commit(tile, start + 1_000, 3)
            lsm.flushNow()
            clock(start + day)
            runBlocking { chrono.mergeAll() }
            lsm.flushNow()
            assertEquals(0, count(Tag.CHRONO_FRAGMENT), "the merged fragments are gone")
            assertEquals(1, capsulesOf(tile))
            assertAnswers("compacted under the merge")
        }

    @Test
    fun `a pinned tile stays out of the glacier`(@TempDir dir: Path) = rig(dir) { clock ->
        val pinned = Tile.of(1, 1, 1)
        val free = Tile.of(1, 2, 2)
        chrono.hint(Hint.Pin(pinned))
        for (d in 0 until 14) for (tile in listOf(pinned, free)) {
            val at = start + d * day
            clock(at)
            commit(tile, at, 4)
            pass()
        }
        clock(start + 90 * day)
        repeat(4) { pass() }
        assertAnswers("pinned")
        assertTrue(capsulesOf(free) < capsulesOf(pinned), "free ${capsulesOf(free)}, pinned ${capsulesOf(pinned)}")
        assertEquals(0, coldOf(pinned), "a pinned tile is read a lot and stays warm")
        assertEquals(capsulesOf(free), coldOf(free))
    }

    @Test
    fun `a full capsule of a closed day is lithified alone, and answers the same`(@TempDir dir: Path) = rig(dir) { clock ->
        val tile = Tile.of(1, 6, 6)
        clock(start)
        commit(tile, start, Chrono.CAPSULE_ROWS)
        runBlocking { chrono.mergeAll() }
        assertEquals(1, capsulesOf(tile))
        assertEquals(0, coldOf(tile), "the day is still open")
        clock(start + day)
        repeat(3) { pass() }
        assertEquals(1, coldOf(tile))
        assertAnswers("cold alone")
    }

    @Test
    fun `a closed day uses the burst points stored beside it`(@TempDir dir: Path) = rig(dir) { clock ->
        val tile = Tile.of(1, 0, 0)
        val cx = 8.5; val cy = 64.5; val cz = 8.5
        val cells = ArrayList<Triple<Int, Int, Int>>()
        for (x in 0..15) for (y in 56..72) for (z in 0..15) {
            val d = (x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy) + (z + 0.5 - cz) * (z + 0.5 - cz)
            if (d <= 36.0 && ((x * 31 + y * 17 + z * 7) % 5 != 0)) cells += Triple(x, y, z)
        }
        val order = PlaceOrder.of(cells.size, { cells[it].first }, { cells[it].second }, { cells[it].third })
        clock(start)
        runBlocking {
            storage.write {
                val burst = Burst(cx, cy, cz)
                put(Bursts.key(tile.world, start, burst), Bursts.value(burst))
                val broken = cells.toHashSet()
                for (cubeY in 3..4) {
                    val bits = LongArray(64)
                    for (i in 0 until 4096) {
                        val x = i and 15; val z = (i shr 4) and 15; val y = cubeY * 16 + (i shr 8)
                        val d = (x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy) + (z + 0.5 - cz) * (z + 0.5 - cz)
                        val empty = y > 72 || (d <= 36.0 && Triple(x, y, z) !in broken)
                        if (empty) bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
                    }
                    put(Voids.key(tile, start, cubeY), Voids.value(bits))
                }
                val writer = chrono.writer(this, Lane.BLOCKS)
                for (at in order) {
                    val (x, y, z) = cells[at]
                    seq++
                    writer.add(tile, seq, start, Cell.of(x, y, z).packed, 41, 0x11, 3, 0, null, null)
                    oracle.merge(tile to Cell.of(x, y, z).packed, 1, Int::plus)
                }
            }
            chrono.mergeAll()
        }
        clock(start + day)
        repeat(3) { pass() }
        assertEquals(1, coldOf(tile))
        assertEquals(0, count(Tag.CHRONO_VOID), "a pressed capsule carries its maps; the store's copies go")
        assertAnswers("rayed")
        val stored = StorageUnit.open(storage.engine).use { unit ->
            unit.scan(ChronoKeys.capsules(tile, Lane.BLOCKS)).use { c -> c.next(); c.value().byteSize() }
        }
        val rows = StorageUnit.open(storage.engine).use { unit ->
            unit.scan(ChronoKeys.capsules(tile, Lane.BLOCKS)).use { c -> c.next(); Lane.BLOCKS.open(c.value()).rows() }
        }
        val blind = LithCapsule.encode(rows, 0).size
        assertTrue(stored < blind, "with its point the crater took $stored B, without $blind B")
    }

    @Test
    fun `a burst holds merging back until it lets go`(@TempDir dir: Path) = rig(
        dir,
        DiagenesisSettings(burstHoldMillis = 120_000L, lingerMillis = 1_000L, payoff = 0.01)
    ) { clock ->
        val tile = Tile.of(1, 5, 5)
        clock(start)
        commit(tile, start, 3)
        commit(tile, start + 1, 3)
        chrono.hint(Hint.Burst(listOf(tile)))
        clock(start + 60_000L)
        assertTrue(chrono.diagenesis.due(start + 60_000L, all = false, limit = 10).isEmpty(), "held")
        assertTrue(chrono.diagenesis.due(start + 200_000L, all = false, limit = 10).isNotEmpty(), "released")
    }

    @Test
    fun `a retired world is sealed at once`(@TempDir dir: Path) = rig(dir) { clock ->
        val tile = Tile.of(2, 0, 0)
        for (i in 0 until 40) {
            val at = start + i * 1_000L
            clock(at)
            commit(tile, at)
            chrono.quiet(tile)
            pass()
        }
        runBlocking { chrono.mergeAll() }
        val before = capsulesOf(tile)
        chrono.hint(Hint.Retire(tile.world))
        repeat(3) { pass() }
        assertTrue(capsulesOf(tile) <= before)
        assertEquals(1, capsulesOf(tile))
        assertAnswers("retired")
    }

    @Test
    fun `sizes stay tiered, not one rewrite per merge`(@TempDir dir: Path) = rig(dir) { clock ->
        val tile = Tile.of(1, 9, 9)
        for (i in 0 until 600) {
            val at = start + i * 30_000L
            clock(at)
            commit(tile, at, 4)
            chrono.quiet(tile)
            pass()
        }
        val d = chrono.stats().diagenesis
        assertTrue(d.siltBytes < 12 * d.newBytes, "silt wrote ${d.siltBytes} B for ${d.newBytes} B of fragments")
        assertAnswers("tiered")
    }
}
