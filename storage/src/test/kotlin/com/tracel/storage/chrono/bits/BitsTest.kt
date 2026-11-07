package com.tracel.storage.chrono.bits

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import kotlin.random.Random

class BitsTest {
    private fun segmentOf(sink: ByteWriter): MemorySegment = MemorySegment.ofArray(sink.toByteArray(BitUnpack.PADDING))

    @Test
    fun `every width from 0 to 64 round-trips at every offset`() {
        val random = Random(7)
        for (bits in 0..64) {
            for (lead in 0..9) {
                val n = random.nextInt(1, 200)
                val values = LongArray(n) { random.nextLong() and lowMask(bits) }
                val sink = ByteWriter()
                repeat(lead) { sink.u8(0xA5) }
                BitPack.longs(sink, values, n, bits)
                assertEquals(lead + packedBytes(n, bits), sink.size, "size for $bits bits")
                val seg = segmentOf(sink)
                for (i in 0 until n) assertEquals(
                    values[i],
                    BitUnpack.get(seg, lead.toLong(), bits, i),
                    "value $i of $bits bits"
                )
            }
        }
    }

    @Test
    fun `int columns decode the same through every path`() {
        val random = Random(11)
        for (bits in 0..32) {
            val n = 333
            val values = IntArray(n) { (random.nextLong() and lowMask(bits)).toInt() }
            val sink = ByteWriter()
            sink.u8(1)
            BitPack.ints(sink, values, n, bits)
            val seg = segmentOf(sink)
            val out = IntArray(n)
            BitUnpack.ints(seg, 1, bits, n, out)
            assertArrayEquals(values, out, "ints at $bits bits")
            for (i in 0 until n) assertEquals(values[i], BitUnpack.int(seg, 1, bits, i))
        }
    }

    @Test
    fun `a palette column maps on the way out`() {
        val palette = intArrayOf(9, 40, 77, 1_000_003)
        val idx = IntArray(100) { it % 4 }
        val sink = ByteWriter()
        BitPack.ints(sink, idx, idx.size, 2)
        val out = IntArray(idx.size)
        BitUnpack.mapped(segmentOf(sink), 0, 2, idx.size, palette, out)
        assertArrayEquals(IntArray(idx.size) { palette[it % 4] }, out)
    }

    @Test
    fun `framed deltas rebuild a climbing column in one pass`() {
        val random = Random(3)
        val n = 500
        val seqs = LongArray(n)
        seqs[0] = 1_000_000_000L
        for (i in 1 until n) seqs[i] = seqs[i - 1] + 3 + random.nextInt(4000)
        val deltas = LongArray(n - 1) { seqs[it + 1] - seqs[it] }
        val floor = deltas.min()
        val bits = widthOf(deltas.max() - floor)
        val sink = ByteWriter()
        BitPack.framed(sink, deltas, n - 1, floor, bits)
        val out = LongArray(n)
        out[0] = seqs[0]
        BitUnpack.deltas(segmentOf(sink), 0, bits, n - 1, seqs[0], floor, out, 1)
        assertArrayEquals(seqs, out)
    }

    @Test
    fun `zigzag deltas survive a clock that steps back`() {
        val random = Random(5)
        val n = 300
        val millis = LongArray(n)
        millis[0] = 1_700_000_000_000L
        for (i in 1 until n) millis[i] = millis[i - 1] + random.nextInt(-40, 900)
        val zz = LongArray(n - 1) { zigzag(millis[it + 1] - millis[it]) }
        val floor = zz.min()
        val bits = widthOf(zz.max() - floor)
        val sink = ByteWriter()
        BitPack.framed(sink, zz, n - 1, floor, bits)
        val out = LongArray(n)
        out[0] = millis[0]
        BitUnpack.zigzagDeltas(segmentOf(sink), 0, bits, n - 1, millis[0], floor, out, 1)
        assertArrayEquals(millis, out)
    }

    @Test
    fun `select keeps exactly the admitted indexes, in order`() {
        val random = Random(9)
        for (bits in 0..10) {
            val n = 777
            val values = IntArray(n) { (random.nextLong() and lowMask(bits)).toInt() }
            val admitted = LongArray(((1 shl bits) + 63) / 64 + 1)
            val keep = (0 until (1 shl bits)).filter { random.nextBoolean() || bits == 0 }.toSet()
            for (v in keep) admitted[v ushr 6] = admitted[v ushr 6] or (1L shl v)
            val sink = ByteWriter()
            BitPack.ints(sink, values, n, bits)
            val seg = segmentOf(sink)
            val selection = IntArray(n)
            val kept = BitUnpack.select(seg, 0, bits, n, admitted, selection)
            val expected = (0 until n).filter { values[it] in keep }
            assertEquals(expected, selection.take(kept), "select at $bits bits")

            val half = LongArray(admitted.size)
            for (v in keep.filter { it % 2 == 0 }) half[v ushr 6] = half[v ushr 6] or (1L shl v)
            val narrowed = BitUnpack.refine(seg, 0, bits, selection, kept, half)
            assertEquals(expected.filter { values[it] % 2 == 0 }, selection.take(narrowed), "refine at $bits bits")
        }
    }

    @Test
    fun `varints and the cursor agree`() {
        val sink = ByteWriter(4)
        val values = longArrayOf(0, 1, 127, 128, 300, Long.MAX_VALUE, -1L, 1L shl 56)
        for (v in values) sink.varint(v)
        sink.signed(-5)
        sink.varint(-1)
        sink.u32(0x12345678)
        sink.u64(-2L)
        val cursor = SegmentReader(segmentOf(sink))
        for (v in values) assertEquals(v, cursor.varint())
        assertEquals(-5, cursor.signed())
        assertEquals(0xFFFF_FFFFL, cursor.varint())
        assertEquals(0x12345678, cursor.u32())
        assertEquals(-2L, cursor.u64())
    }

    @Test
    fun `widths and zigzag`() {
        assertEquals(0, widthOf(0L))
        assertEquals(1, widthOf(1L))
        assertEquals(64, widthOf(-1L))
        assertEquals(0, indexWidth(1))
        assertEquals(1, indexWidth(2))
        assertEquals(2, indexWidth(3))
        for (v in longArrayOf(0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, -12345)) assertEquals(v, unzigzag(zigzag(v)))
        assertEquals(3L, zigzag(-2))
    }
}
