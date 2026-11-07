package com.tracel.storage.chrono

import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
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

        fun assertAnswers(label: String) = runBlocking {
            storage.read {
                val reader = chrono.reader(this)
                for ((key, want) in oracle) {
                    assertEquals(want, reader.history(key.first, Cell(key.second)).size, "$label: ${key.first} ${Cell(key.second)}")
                }
            }
        }
    }

    private fun rig(dir: Path, diagenesis: DiagenesisSettings = DiagenesisSettings(), block: Rig.(setClock: (Long) -> Unit) -> Unit) {
        var now = start
        TracelStorage.open(dir) { LsmEngine(it, History.configured(LsmOptions())) }.use { storage ->
            val chrono = Chrono.of(
                storage,
                ChronoSettings(passMillis = 1_000_000_000_000L, clock = { now }, diagenesis = diagenesis),
            )
            Rig(storage, chrono) { now }.block { now = it }
        }
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
