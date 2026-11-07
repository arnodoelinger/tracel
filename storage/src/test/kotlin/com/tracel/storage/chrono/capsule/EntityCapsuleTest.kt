package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.RowsBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import kotlin.random.Random

class EntityCapsuleTest {
    private fun entities(seed: Int, n: Int): Rows {
        val random = Random(seed)
        val b = RowsBuilder()
        var seq = 5_000L
        var millis = 1_700_000_000_000L
        val regulars = List(4) { random.nextLong() to random.nextLong() }
        repeat(n) {
            seq += 1 + random.nextInt(3)
            millis += random.nextLong(0, 2000)
            val (high, low) = if (random.nextInt(3) == 0) regulars[random.nextInt(4)] else random.nextLong() to random.nextLong()
            val spawn = random.nextBoolean()
            val shape = if (random.nextInt(4) == 0) null else ByteArray(random.nextInt(1, 90)) { it.toByte() }
            b.addEntity(
                seq, millis, Cell.of(random.nextInt(16), 60 + random.nextInt(8), random.nextInt(16)).packed,
                if (random.nextInt(5) == 0) 0 else 41 + random.nextInt(3), random.nextInt(4) shl 4 or (if (spawn) 4 else 5),
                700 + random.nextInt(6), high, low, if (spawn) null else shape, if (spawn) shape else null,
            )
        }
        return b.rows()
    }

    @Test
    fun `entity rows come back whole, uuid and shapes included`() {
        for (seed in 1..10) {
            val rows = entities(seed, 30 + seed * 211)
            val bytes = Lane.ENTITIES.encode(rows, 19_000)
            val view = Lane.ENTITIES.open(MemorySegment.ofArray(bytes)) as EntityCapsule
            assertEquals(rows.size, view.rowCount)
            assertEquals(19_000L, view.window)
            val back = view.rows()
            for (i in 0 until rows.size) {
                assertEquals(rows.seq[i], back.seq[i])
                assertEquals(rows.millis[i], back.millis[i])
                assertEquals(rows.cell[i], back.cell[i])
                assertEquals(rows.actor[i], back.actor[i])
                assertEquals(rows.kind[i], back.kind[i])
                assertEquals(rows.after[i], back.after[i], "type")
                assertEquals(rows.uuidHigh!![i], back.uuidHigh!![i])
                assertEquals(rows.uuidLow!![i], back.uuidLow!![i])
                assertArrayEquals(rows.beforeExtrasAt(i), back.beforeExtrasAt(i))
                assertArrayEquals(rows.afterExtrasAt(i), back.afterExtrasAt(i))
            }
            assertArrayEquals(rows.seq.copyOf(rows.size), view.seqs())
            assertEquals(rows.cell.copyOf(rows.size).distinct().sorted(), view.cells.toList())
        }
    }
}
