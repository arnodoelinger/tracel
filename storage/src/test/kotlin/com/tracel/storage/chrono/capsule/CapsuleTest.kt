package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.*
import com.tracel.storage.chrono.bits.BitUnpack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CapsuleTest {
    private val encoder = CapsuleEncoder()

    private fun sortedCopy(rows: Rows): Rows {
        val order = (0 until rows.size).sortedBy { rows.seq[it] }
        fun ints(a: IntArray) = IntArray(rows.size) { a[order[it]] }
        return Rows(
            rows.size, LongArray(rows.size) { rows.seq[order[it]] }, LongArray(rows.size) { rows.millis[order[it]] },
            ints(rows.cell), ints(rows.actor), ints(rows.kind), ints(rows.before), ints(rows.after),
            rows.beforeExtras?.let { e -> Array(rows.size) { e[order[it]] } },
            rows.afterExtras?.let { e -> Array(rows.size) { e[order[it]] } },
        )
    }

    private fun assertSameRows(expected: Rows, actual: Rows) {
        assertEquals(expected.size, actual.size, "row count")
        for (i in 0 until expected.size) {
            val at = "row $i"
            assertEquals(expected.seq[i], actual.seq[i], "$at seq")
            assertEquals(expected.millis[i], actual.millis[i], "$at millis")
            assertEquals(Cell(expected.cell[i]), Cell(actual.cell[i]), "$at cell")
            assertEquals(expected.actor[i], actual.actor[i], "$at actor")
            assertEquals(expected.kind[i], actual.kind[i], "$at kind")
            assertEquals(expected.before[i], actual.before[i], "$at before")
            assertEquals(expected.after[i], actual.after[i], "$at after")
            assertArrayEquals(expected.beforeExtrasAt(i), actual.beforeExtrasAt(i), "$at before extras")
            assertArrayEquals(expected.afterExtrasAt(i), actual.afterExtrasAt(i), "$at after extras")
        }
    }

    @Test
    fun `a day of a busy tile comes back exactly`() {
        for (seed in 1..20) {
            val world = Worldlike(seed, breakRate = if (seed % 3 == 0) 0.02 else 0.0)
            val open = world.open(n = 50 + seed * 97)
            val rows = open.rows()
            val bytes = encoder.encode(rows, 19_000)
            val capsule = Capsule.of(bytes)
            assertEquals(rows.size, capsule.rowCount)
            assertEquals(19_000L, capsule.window)
            assertEquals(world.injectedBreaks, capsule.breaks().size, "breaks found for seed $seed")
            assertEquals(world.injectedBreaks, encoder.lastBreaks)
            assertSameRows(sortedCopy(rows), capsule.rows())
        }
    }

    @Test
    fun `entries and exits are the first before and the last after of every cell`() {
        val rows = Worldlike(5).open(n = 3000).rows()
        val capsule = Capsule.of(encoder.encode(rows, 0))
        val firstBefore = LinkedHashMap<Int, Int>()
        val lastAfter = HashMap<Int, Int>()
        for (i in 0 until rows.size) {
            firstBefore.putIfAbsent(rows.cell[i], rows.before[i])
            lastAfter[rows.cell[i]] = rows.after[i]
        }
        val cells = capsule.cells
        assertEquals(firstBefore.keys.sorted(), cells.toList())
        val entries = capsule.entries()
        val exits = capsule.exits()
        for (c in cells.indices) {
            assertEquals(firstBefore[cells[c]], entries[c], "entry of ${Cell(cells[c])}")
            assertEquals(lastAfter[cells[c]], exits[c], "exit of ${Cell(cells[c])}")
            assertEquals(c, capsule.indexOfCell(Cell(cells[c])))
        }
        assertEquals(-1, capsule.indexOfCell(Cell.of(15, 300, 15)))
    }

    @Test
    fun `rows that arrive out of order are sorted on the way in`() {
        val rows = Worldlike(8).open(n = 1500).rows()
        val shuffle = (0 until rows.size).shuffled(Random(3))
        val open = RowsBuilder()
        for (i in shuffle) {
            open.add(
                rows.seq[i], rows.millis[i], rows.cell[i], rows.actor[i], rows.kind[i], rows.before[i], rows.after[i],
                rows.beforeExtrasAt(i), rows.afterExtrasAt(i),
            )
        }
        assertTrue(!open.rows().isSorted())
        val capsule = Capsule.of(encoder.encode(open.rows(), 0))
        assertSameRows(sortedCopy(rows), capsule.rows())
        assertArrayEquals(LongArray(rows.size) { rows.seq[it] }.sortedArray(), capsule.seqs())
    }

    @Test
    fun `one row, and negative heights`() {
        val open = RowsBuilder()
        open.add(42, 1000, Cell.of(-17, -64, 31).packed, 0, 5, 3, 4, null, byteArrayOf(1, 2, 3))
        val capsule = Capsule.of(encoder.encode(open.rows(), -2))
        val rows = capsule.rows()
        assertEquals(1, rows.size)
        assertEquals(Cell.of(-17, -64, 31), rows.cellAt(0))
        assertEquals(-64, rows.cellAt(0).y)
        assertEquals(15, rows.cellAt(0).x)
        assertEquals(42L, capsule.firstSeq)
        assertEquals(42L, capsule.lastSeq)
        assertEquals(-2L, capsule.window)
        assertArrayEquals(byteArrayOf(1, 2, 3), rows.afterExtrasAt(0))
        assertArrayEquals(byteArrayOf(1, 2, 3), capsule.exitExtras(0))
    }

    @Test
    fun `the padding covers every word a column read can take`() {
        val rows = Worldlike(13).open(n = 777).rows()
        val bytes = encoder.encode(rows, 0)
        val capsule = Capsule.of(bytes)
        assertEquals(capsule.encodedBytes + BitUnpack.PADDING, bytes.size.toLong())
        capsule.rows()
    }

    @Test
    fun `tiles and cells pack and unpack`() {
        for ((w, x, z) in listOf(
            Triple(0, 0, 0),
            Triple(5, -1, -1),
            Triple(1 shl 19, 2_000_000, -2_000_000),
            Triple(77, -1875000, 1875000)
        )) {
            val tile = Tile.of(w, x, z)
            assertEquals(w, tile.world)
            assertEquals(x, tile.x)
            assertEquals(z, tile.z)
        }
        assertEquals(Tile.of(3, -1, 0), Tile.at(3, -5, 9))
        val cell = Cell.of(-1, -2048, 17)
        assertEquals(15, cell.x)
        assertEquals(1, cell.z)
        assertEquals(-2048, cell.y)
        assertTrue(Cell.of(0, -1, 0).packed < Cell.of(0, 0, 0).packed)
        assertTrue(Cell.of(15, 5, 15).packed < Cell.of(0, 6, 0).packed)
    }
}
