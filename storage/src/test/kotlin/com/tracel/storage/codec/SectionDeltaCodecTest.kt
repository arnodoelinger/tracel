package com.tracel.storage.codec

import com.tracel.model.cause.CauseKind
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.entity.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import java.util.*

class SectionDeltaCodecTest {
    private class Built(
        val positions: IntArray,
        val before: IntArray,
        val after: IntArray,
        val bytes: ByteArray,
    ) {
        val value: MemorySegment get() = MemorySegment.ofArray(bytes)
    }

    private fun build(
        count: Int,
        palette: Int = 4,
        stride: Int = 1,
        extras: List<SectionExtras> = emptyList(),
    ): Built {
        val positions = IntArray(count) { it * stride }
        val before = IntArray(count) { 100 + (it % palette) }
        val after = IntArray(count) { if (it % 7 == 0) 0 else 200 + (it % palette) }
        val bytes = Section.delta(
            ActionKind.BLOCK_CHANGE, CauseKind.EXPLOSION, causedBy = 77, world = 3,
            sectionX = -5, sectionY = 2, sectionZ = 9, millis = 1_700_000_000_123L,
            baseSeq = 9_000L, positions = positions, count = count,
            before = before, after = after, extras = extras,
        )
        return Built(positions, before, after, bytes)
    }

    private fun checkRoundTrip(built: Built) {
        val v = built.value
        assertEquals(Wchg.SECTION, Wchg.kind[v])
        assertEquals(ActionKind.BLOCK_CHANGE, Wchg.action[v])
        assertEquals(CauseKind.EXPLOSION, Wchg.cause[v])
        assertEquals(77, Wchg.causedBy[v])
        assertEquals(3, Wchg.world[v])
        assertEquals(1_700_000_000_123L, Wchg.epochMillis[v])
        assertEquals(9_000L, Section.baseSeq(v))
        assertEquals(built.positions.size, Section.count(v))

        assertEquals(-5 shl 4, Wchg.x[v])
        assertEquals(2 shl 4, Wchg.y[v])
        assertEquals(9 shl 4, Wchg.z[v])

        val seenIndex = ArrayList<Int>()
        val seenPacked = ArrayList<Int>()
        Section.forEachPosition(v) { index, packed ->
            seenIndex += index
            seenPacked += packed
        }
        assertEquals(built.positions.toList(), seenPacked, "positions must come back ascending and complete")
        assertEquals(built.positions.indices.toList(), seenIndex)

        for (i in built.positions.indices) {
            assertEquals(built.before[i], Section.before(v, i), "before at $i")
            assertEquals(built.after[i], Section.after(v, i), "after at $i")
            assertEquals(i, Section.indexOf(v, built.positions[i]), "index of position $i")
        }
    }

    @Test
    fun `a sparse delta round-trips every position and state`() {
        val built = build(count = 30, stride = 7)
        assertTrue(!Section.isBitmap(built.value), "thirty positions are cheaper as a list")
        checkRoundTrip(built)
    }

    @Test
    fun `a dense delta round-trips every position and state`() {
        val built = build(count = 4096)
        assertTrue(Section.isBitmap(built.value), "a full section is cheaper as a bitmap")
        checkRoundTrip(built)
    }

    @Test
    fun `both encodings agree about which positions are absent`() {
        for (built in listOf(build(count = 30, stride = 7), build(count = 4096))) {
            val present = built.positions.toHashSet()
            for (packed in 0 until Section.POSITIONS) {
                if (packed in present) continue
                assertEquals(-1, Section.indexOf(built.value, packed), "position $packed was not touched")
            }
        }
    }

    @Test
    fun `a palette wider than a byte still indexes correctly`() {
        val count = 700
        val positions = IntArray(count) { it }
        val before = IntArray(count) { 1000 + it }
        val after = IntArray(count) { 5000 + it }
        val bytes = Section.delta(
            ActionKind.BLOCK_CHANGE, CauseKind.PLAYER_ACTION, 1, 1, 0, 0, 0, 1L, 0L,
            positions, count, before, after,
        )
        val v = MemorySegment.ofArray(bytes)
        assertTrue(Section.paletteSize(v) > 256, "this test is pointless with a narrow palette")
        for (i in 0 until count) {
            assertEquals(before[i], Section.before(v, i), "before at $i")
            assertEquals(after[i], Section.after(v, i), "after at $i")
        }
    }

    @Test
    fun `tile entity payloads come back only for the positions that carried them`() {
        val extras = listOf(
            SectionExtras(2, byteArrayOf(1, 2, 3), ByteArray(0)),
            SectionExtras(17, ByteArray(0), byteArrayOf(9, 9, 9, 9)),
        )
        val built = build(count = 30, stride = 7, extras = extras)
        checkRoundTrip(built)

        val v = built.value
        assertArrayEquals(byteArrayOf(1, 2, 3), Section.extras(v, 2)!!.before)
        assertEquals(0, Section.extras(v, 2)!!.after.size)
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), Section.extras(v, 17)!!.after)
        assertNull(Section.extras(v, 3), "a position with no tile entity must not borrow its neighbour's")
        assertNull(Section.extras(v, 29))
    }

    @Test
    fun `a position packs and unpacks to itself`() {
        for (y in 0 until 16) {
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val packed = Section.position(x, y, z)
                    assertEquals(x, Section.positionX(packed))
                    assertEquals(y, Section.positionY(packed))
                    assertEquals(z, Section.positionZ(packed))
                    assertTrue(packed in 0 until Section.POSITIONS)
                }
            }
        }
    }

    @Test
    fun `packing takes a block's offset within its section, whatever its world coordinates`() {
        assertEquals(
            Section.position(15, 15, 15),
            Section.position(-1, -1, -1),
            "-1 sits at offset 15 of the section below, not at -1 of this one",
        )
    }

    @Test
    fun `a falling block keeps its data and nothing else`() {
        val extras = EntityExtras.Falling(
            BlockDataKey("minecraft:sand")
        )
        val bytes = Extras.entity(extras)
        assertEquals(extras, Extras.decodeEntity(bytes))

        assertTrue(bytes.size < 32, "a falling block's extras came to ${bytes.size} bytes")
    }

    @Test
    fun `entity extras still round-trip the opaque kind beside the falling one`() {
        val opaque = EntityExtras.Opaque(ByteArray(40) { it.toByte() })
        assertEquals(opaque, Extras.decodeEntity(Extras.entity(opaque)))
        assertNull(Extras.decodeEntity(Extras.entity(null)))
    }

    @Test
    fun `a leash keeps its holder and the snapshot it wraps`() {
        val knot = UUID.fromString("6f3a1f5e-0c11-4f2a-9a3e-2f9e7c1b4d55")
        val leashed = EntityExtras.Leashed(
            knot,
            EntityExtras.Opaque(ByteArray(40) { it.toByte() }),
        )
        assertEquals(leashed, Extras.decodeEntity(Extras.entity(leashed)))
    }

    @Test
    fun `a leash survives with nothing wrapped in it`() {
        val bare = EntityExtras.Leashed(UUID.randomUUID(), null)
        assertEquals(bare, Extras.decodeEntity(Extras.entity(bare)))
    }

    @Test
    fun `a seat keeps its vehicle, and stacks with a leash`() {
        val boat = UUID.randomUUID()
        val knot = UUID.randomUUID()
        val both = EntityExtras.Riding(
            boat,
            EntityExtras.Leashed(
                knot,
                EntityExtras.Opaque(ByteArray(12) { it.toByte() }),
            ),
        )
        val decoded = Extras.decodeEntity(Extras.entity(both))
        assertEquals(both, decoded)
        assertEquals(boat, decoded.vehicle)
        assertEquals(knot, decoded.leashHolder)
        assertEquals(12, decoded.opaque?.bytes?.size)
    }

    @Test
    fun `a leashed shape round-trips through the payload the world log stores`() {
        val type = EntityTypeKey("minecraft:cow")
        val at = BlockPos(WorldId(UUID.randomUUID()), 1, 2, 3)
        val shape = EntityShape(
            type, 1.5, 2.0, 3.5, 90f, -12.5f,
            EntityExtras.Leashed(
                UUID.randomUUID(),
                EntityExtras.Opaque(ByteArray(8) { it.toByte() }),
            ),
        )
        assertEquals(shape, Extras.decodeShape(type, at, Extras.shape(shape)))
    }

    @Test
    fun `a shape is read where it lies in a bigger record, nested links and all`() {
        val type = EntityTypeKey("minecraft:horse")
        val at = BlockPos(WorldId(UUID.randomUUID()), 4, 5, 6)
        val shape = EntityShape(
            type, 8.5, 64.0, -3.5, 10f, 20f,
            EntityExtras.Riding(
                UUID.randomUUID(),
                EntityExtras.Leashed(UUID.randomUUID(), EntityExtras.Opaque(ByteArray(5) { (it + 1).toByte() })),
            ),
        )
        val payload = Extras.shape(shape)
        val lead = 37
        val record = ByteArray(lead + payload.size + 9)
        System.arraycopy(payload, 0, record, lead, payload.size)

        val read = Extras.decodeShape(type, at, MemorySegment.ofArray(record).asSlice(lead.toLong(), payload.size.toLong()))
        assertEquals(shape, read)
        assertNull(Extras.decodeShape(type, at, MemorySegment.ofArray(record).asSlice(lead.toLong(), 0)))
        assertNull(Extras.decodeEntity(MemorySegment.ofArray(record).asSlice(lead.toLong(), 0)))
    }
}
