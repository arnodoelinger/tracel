package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.*
import com.tracel.storage.chrono.lith.Burst
import com.tracel.storage.chrono.lith.Bursts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import kotlin.random.Random

class LithCapsuleTest {
    private fun assertSameRows(expected: Rows, actual: Rows, label: String) {
        assertEquals(expected.size, actual.size, "$label: row count")
        for (i in 0 until expected.size) {
            val at = "$label: row $i"
            assertEquals(expected.seq[i], actual.seq[i], "$at seq")
            assertEquals(expected.millis[i], actual.millis[i], "$at millis")
            assertEquals(Cell(expected.cell[i]), Cell(actual.cell[i]), "$at cell")
            assertEquals(expected.actor[i], actual.actor[i], "$at actor")
            assertEquals(expected.kind[i], actual.kind[i], "$at kind")
            assertEquals(expected.before[i], actual.before[i], "$at before")
            assertEquals(expected.after[i], actual.after[i], "$at after")
            assertArrayEquals(expected.beforeExtrasAt(i)?.takeIf { it.isNotEmpty() }, actual.beforeExtrasAt(i), "$at before extras")
            assertArrayEquals(expected.afterExtrasAt(i)?.takeIf { it.isNotEmpty() }, actual.afterExtrasAt(i), "$at after extras")
        }
    }

    private fun roundTrip(rows: Rows, label: String, window: Long = 7): LithCapsule {
        val bytes = LithCapsule.encode(rows, window)
        val view = Lane.BLOCKS.open(MemorySegment.ofArray(bytes))
        assertTrue(view is LithCapsule, "$label: the lane opens it as lithified")
        view as LithCapsule
        val sorted = rows.sorted()
        assertSameRows(sorted, view.rows(), label)
        val n = sorted.size
        assertEquals(n, view.rowCount)
        assertEquals(sorted.seq[0], view.firstSeq)
        assertEquals(sorted.seq[n - 1], view.lastSeq)
        assertEquals(sorted.millis.take(n).min(), view.minMillis)
        assertEquals(sorted.millis.take(n).max(), view.maxMillis)
        assertEquals(window, view.window)
        assertEquals(sorted.actor.take(n).distinct().sorted(), view.actors.toList(), "$label: actors")
        assertEquals(sorted.kind.take(n).distinct().sorted(), view.kinds.toList(), "$label: kinds")
        assertEquals(sorted.cell.take(n).distinct().sorted(), view.cells.toList(), "$label: cells")
        assertEquals(view.cells.size, view.cellCount)
        return view
    }

    @Test
    fun `a busy tile comes back exactly, breaks and sign text included`() {
        for (seed in 1..20) {
            val rows = Worldlike(seed, breakRate = if (seed % 3 == 0) 0.02 else 0.0).open(n = 50 + seed * 97).rows()
            roundTrip(rows, "seed $seed")
        }
    }

    private fun blasts(seed: Int, count: Int): Rows {
        val random = Random(seed)
        val world = HashMap<Int, Int>()
        val out = RowsBuilder()
        var seq = 5_000_000L
        var millis = 1_700_000_000_000L
        repeat(count) {
            val cx = random.nextInt(0, 16)
            val cz = random.nextInt(0, 16)
            val cy = random.nextInt(40, 70)
            val r = random.nextInt(2, 6)
            val cells = ArrayList<Triple<Int, Int, Int>>()
            for (x in cx - r..cx + r) for (z in cz - r..cz + r) for (y in cy - r..cy + r) {
                if (x !in 0..15 || z !in 0..15) continue
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz) > r * r) continue
                val cell = Cell.of(x, y, z).packed
                if ((world[cell] ?: 1) == 0) continue
                cells += Triple(x, y, z)
            }
            cells.shuffle(random)
            val order = PlaceOrder.of(cells.size, { cells[it].first }, { cells[it].second }, { cells[it].third })
            val actor = 100 + random.nextInt(4)
            for (at in order) {
                val (x, y, z) = cells[at]
                val cell = Cell.of(x, y, z).packed
                val before = world[cell] ?: (1 + random.nextInt(0, 3))
                out.add(seq++, millis, cell, actor, 0x11, before, 0)
                world[cell] = 0
            }
            seq += random.nextLong(1, 40_000)
            millis += random.nextLong(50, 90_000)
        }
        return out.rows()
    }

    @Test
    fun `blasts numbered by place press well under a warm capsule`() {
        val rows = blasts(4, 60)
        val view = roundTrip(rows, "blasts")
        val warm = CapsuleEncoder().encode(rows, 7).size
        val cold = view.bytes.byteSize()
        assertTrue(cold * 3 < warm * 2, "cold $cold bytes against warm $warm for ${rows.size} rows")
    }

    @Test
    fun `ids, gaps and palettes past every small limit come back`() {
        val random = Random(9)
        val out = RowsBuilder()
        var seq = 1L
        var millis = -5_000L
        for (i in 0 until 4000) {
            seq += if (i % 997 == 0) 1L shl 40 else random.nextLong(1, 3)
            millis += if (i % 1301 == 0) 40L * 86_400_000L else random.nextLong(-50, 200)
            val cell = Cell.of(random.nextInt(16), random.nextInt(-2000, 3000), random.nextInt(16)).packed
            val actor = if (i % 7 == 0) 0 else random.nextInt(1, 1 shl 29)
            val kind = random.nextInt(0, 400)
            val before = random.nextInt(0, 3000) + if (i % 11 == 0) (1 shl 28) else 0
            val after = random.nextInt(0, 3000)
            val be = if (i % 101 == 0) byteArrayOf(1, 2, 3) else null
            val ae = when (i % 151) {
                0 -> byteArrayOf(9)
                1 -> be ?: byteArrayOf(4, 4)
                else -> null
            }
            out.add(seq, millis, cell, actor, kind, before, after, be, ae)
        }
        roundTrip(out.rows(), "wide", window = -3)
    }

    @Test
    fun `rows out of order, single rows and same-cell runs come back`() {
        val one = RowsBuilder().apply { add(42, 1000, Cell.of(3, -64, 9).packed, 5, 2, 7, 8) }.rows()
        roundTrip(one, "one row")
        val same = RowsBuilder().apply {
            for (i in 0 until 50) add(100L + i, 2000, Cell.of(1, 64, 1).packed, 5, 2, i, i + 1)
        }.rows()
        roundTrip(same, "one cell, fifty times")
        val twice = RowsBuilder().apply {
            add(7, 10, Cell.of(1, 64, 1).packed, 5, 2, 1, 0)
            add(7, 10, Cell.of(1, 64, 1).packed, 5, 2, 1, 0)
            add(8, 10, Cell.of(2, 64, 1).packed, 5, 2, 1, 0)
        }.rows()
        roundTrip(twice, "a row written twice")
        val rows = Worldlike(8).open(n = 1500).rows()
        val shuffled = rows.pick((0 until rows.size).shuffled(Random(3)).toIntArray())
        roundTrip(shuffled, "shuffled")
    }

    @Test
    fun `a trimmed capsule keeps the bounds it is given`() {
        val rows = Worldlike(2).open(n = 300).rows()
        val view = LithCapsule(MemorySegment.ofArray(LithCapsule.encode(rows, 0, lastSeqAtLeast = Long.MAX_VALUE / 2, maxMillisAtLeast = Long.MAX_VALUE / 4)))
        assertEquals(Long.MAX_VALUE / 2, view.lastSeq)
        assertEquals(Long.MAX_VALUE / 4, view.maxMillis)
    }

    /** A blast the way the game does one: rays off a 16-lattice, weakening with distance and with what they cross. */
    private fun blast(seed: Int, cx: Double, cy: Double, cz: Double, power: Float): Map<Tile, Rows> {
        val random = Random(seed)
        fun solid(x: Int, y: Int, z: Int): Boolean {
            if (y > 62 + ((x * 7 + z * 13) and 3)) return false
            val h = (x * 73856093) xor (y * 19349663) xor (z * 83492791)
            return (h and 15) != 0
        }
        val broken = LinkedHashSet<Triple<Int, Int, Int>>()
        for (i in 0..15) for (j in 0..15) for (k in 0..15) {
            if (i != 0 && i != 15 && j != 0 && j != 15 && k != 0 && k != 15) continue
            var dx = i / 15.0 * 2 - 1; var dy = j / 15.0 * 2 - 1; var dz = k / 15.0 * 2 - 1
            val len = Math.sqrt(dx * dx + dy * dy + dz * dz); dx /= len; dy /= len; dz /= len
            var force = power * (0.7f + random.nextFloat() * 0.6f)
            var x = cx; var y = cy; var z = cz
            while (force > 0f) {
                val bx = Math.floor(x).toInt(); val by = Math.floor(y).toInt(); val bz = Math.floor(z).toInt()
                if (solid(bx, by, bz)) {
                    force -= (0.6f + 0.3f) * 0.3f
                    if (force > 0f) broken += Triple(bx, by, bz)
                }
                x += dx * 0.3; y += dy * 0.3; z += dz * 0.3
                force -= 0.22500001f
            }
        }
        val list = broken.toList().shuffled(random)
        val order = PlaceOrder.of(list.size, { list[it].first }, { list[it].second }, { list[it].third })
        val byTile = LinkedHashMap<Tile, RowsBuilder>()
        var seq = 10_000L
        for (at in order) {
            val (x, y, z) = list[at]
            byTile.getOrPut(Tile.at(1, x, z)) { RowsBuilder() }.add(seq++, 5_000L, Cell.of(x, y, z).packed, 77, 0x11, 1 + ((x + z) and 1), 0)
        }
        return byTile.mapValues { it.value.rows() }
    }

    @Test
    fun `a blast walked along its own rays comes back exactly and much smaller`() {
        for ((seed, power) in listOf(1 to 4f, 2 to 12f, 3 to 40f)) {
            val cx = 8.3 + seed; val cy = 58.6; val cz = -3.7
            val tiles = blast(seed, cx, cy, cz, power)
            val bursts = Bursts { if (it == 5_000L) listOf(Burst(cx, cy, cz), Burst(cx + 90, cy, cz)) else emptyList() }
            var plain = 0L
            var rayed = 0L
            for ((tile, rows) in tiles) {
                val without = LithCapsule.encode(rows, 0)
                val with = LithCapsule.encode(rows, 0, tile = tile, bursts = bursts)
                plain += without.size
                rayed += with.size
                for (bytes in listOf(without, with)) {
                    val view = LithCapsule(MemorySegment.ofArray(bytes))
                    assertSameRows(rows.sorted(), view.rows(), "power $power, $tile")
                }
            }
            val blocks = tiles.values.sumOf { it.size }
            println("power $power: $blocks blocks in ${tiles.size} tiles, walked $plain B, rayed $rayed B")
            if (power >= 12f) assertTrue(rayed * 10 < plain * 8, "power $power: rays $rayed B against $plain B")
        }
    }
}
