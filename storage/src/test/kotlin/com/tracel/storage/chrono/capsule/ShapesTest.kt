package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.RowsBuilder
import com.tracel.storage.chrono.bits.ByteWriter
import com.tracel.storage.chrono.bits.LittleEndian
import com.tracel.storage.chrono.bits.SegmentReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import kotlin.random.Random

class ShapesTest {
    private fun delta(target: ByteArray, ref: ByteArray): ByteArray {
        val sink = ByteWriter()
        ByteDelta.encode(target, ref, sink)
        val ops = sink.toByteArray(16)
        return ByteDelta.decode(SegmentReader(MemorySegment.ofArray(ops)), ref, target.size)
    }

    @Test
    fun `a delta makes the blob back from its sibling, whatever they share`() {
        val random = Random(7)
        repeat(300) {
            val ref = ByteArray(random.nextInt(0, 400)) { random.nextInt(6).toByte() }
            val target = when (it % 4) {
                0 -> ref.copyOf().also { t -> repeat(3) { _ -> if (t.isNotEmpty()) t[random.nextInt(t.size)] = 99 } }
                1 -> ByteArray(random.nextInt(0, 300)) { random.nextInt(256).toByte() }
                2 -> ref.copyOfRange(0, ref.size / 2) + byteArrayOf(1, 2, 3) + ref.copyOfRange(ref.size / 2, ref.size)
                else -> ref.reversedArray()
            }
            assertArrayEquals(target, delta(target, ref))
        }
    }

    @Test
    fun `a sibling that differs in a few places costs a few bytes`() {
        val random = Random(3)
        val ref = ByteArray(600) { random.nextInt(256).toByte() }
        val target = ref.copyOf().also { it[100] = 7; it[101] = 8; it[400] = 9 }
        val sink = ByteWriter()
        ByteDelta.encode(target, ref, sink)
        assertTrue(sink.size < 24, "three changed bytes in 600 took ${sink.size}.")
        assertArrayEquals(target, delta(target, ref))
    }

    private fun pose(x: Double, y: Double, z: Double, yaw: Float, pitch: Float): ByteArray = ByteArray(33).also {
        it[0] = POSED
        LittleEndian.LONG.set(it, 1, x.toBits())
        LittleEndian.LONG.set(it, 9, y.toBits())
        LittleEndian.LONG.set(it, 17, z.toBits())
        LittleEndian.INT.set(it, 25, yaw.toBits())
        LittleEndian.INT.set(it, 29, pitch.toBits())
    }

    private fun read(blob: ByteArray): DoubleArray = doubleArrayOf(
        Double.fromBits(LittleEndian.LONG.get(blob, 1) as Long), Double.fromBits(LittleEndian.LONG.get(blob, 9) as Long),
        Double.fromBits(LittleEndian.LONG.get(blob, 17) as Long), Float.fromBits(LittleEndian.INT.get(blob, 25) as Int).toDouble(),
        Float.fromBits(LittleEndian.INT.get(blob, 29) as Int).toDouble(),
    )

    private fun roundTrip(before: List<ByteArray?>, after: List<ByteArray?>): Rows {
        val b = RowsBuilder()
        for (i in before.indices) {
            b.addEntity(100L + i, 1_000L + i, Cell.of(i % 16, 64, 3).packed, 0, 4, 700 + i % 2, 5L, i.toLong(), before[i], after[i])
        }
        val bytes = Lane.ENTITIES.encode(b.rows(), 1)
        return (Lane.ENTITIES.open(MemorySegment.ofArray(bytes)) as EntityCapsule).rows()
    }

    @Test
    fun `a pose comes back within a millimetre and a hair of a degree`() {
        val random = Random(11)
        val poses = List(200) {
            pose(random.nextDouble(-3e7, 3e7), random.nextDouble(-64.0, 320.0), random.nextDouble(-3e7, 3e7), random.nextFloat() * 360 - 180, random.nextFloat() * 180 - 90)
        }
        val rest = byteArrayOf(5, 1, 2, 3)
        val back = roundTrip(poses.map { it + rest }, List(poses.size) { null })
        for (i in poses.indices) {
            val want = read(poses[i])
            val got = read(back.beforeExtrasAt(i)!!)
            for (axis in 0..2) assertEquals(want[axis], got[axis], 1.0 / 2048 + 1e-9)
            for (axis in 3..4) assertEquals(want[axis], got[axis], 1.0 / 256 + 1e-6)
            assertArrayEquals(rest, back.beforeExtrasAt(i)!!.copyOfRange(33, 37))
            assertEquals(POSED, back.beforeExtrasAt(i)!![0])
        }
    }

    @Test
    fun `a pose that sits on the grid comes back exactly`() {
        val on = pose(12.5, 64.0, -7.0625, 90f, -12.5f)
        val back = roundTrip(listOf(on + byteArrayOf(9)), listOf(null))
        assertArrayEquals(on + byteArrayOf(9), back.beforeExtrasAt(0))
    }

    @Test
    fun `a pose that is no number is kept whole instead`() {
        val odd = pose(Double.NaN, 1.0, 2.0, Float.POSITIVE_INFINITY, 0f) + byteArrayOf(4)
        val back = roundTrip(listOf(odd), listOf(null))
        assertArrayEquals(odd, back.beforeExtrasAt(0))
    }

    @Test
    fun `mobs of one kind share a template and cost only what differs`() {
        val random = Random(5)
        val template = ByteArray(700) { random.nextInt(256).toByte() }
        val mobs = List(60) { i -> template.copyOf().also { it[300] = i.toByte(); it[301] = (i * 3).toByte() } }
        val shared = Lane.ENTITIES.encode(
            RowsBuilder().also { b -> mobs.forEachIndexed { i, m -> b.addEntity(10L + i, 5L + i, Cell.of(i % 16, 64, 0).packed, 0, 4, 701, 1L, i.toLong(), null, m) } }.rows(),
            1,
        )
        assertTrue(shared.size < 60 * 40 + 1_000, "sixty near-identical mobs took ${shared.size} bytes.")
        val back = (Lane.ENTITIES.open(MemorySegment.ofArray(shared)) as EntityCapsule).rows()
        for (i in mobs.indices) assertArrayEquals(mobs[i], back.afterExtrasAt(i))
    }
}
