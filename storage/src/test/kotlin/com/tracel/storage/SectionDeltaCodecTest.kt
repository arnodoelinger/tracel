package com.tracel.storage

import com.tracel.model.transaction.CauseKind
import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.entity.*
import com.tracel.storage.codec.Records
import com.tracel.storage.codec.records.SectionExtras
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
        val bytes = Records.sectionDelta(
            ActionKind.BLOCK_CHANGE, CauseKind.EXPLOSION, causedByHolderId = 77, worldId = 3,
            sectionX = -5, sectionY = 2, sectionZ = 9, epochMillis = 1_700_000_000_123L,
            baseSeq = 9_000L, positions = positions, count = count,
            before = before, after = after, extras = extras,
        )
        return Built(positions, before, after, bytes)
    }

    private fun checkRoundTrip(built: Built) {
        val v = built.value
        assertEquals(Records.CHANGE_SECTION, Records.wchgKind(v))
        assertEquals(ActionKind.BLOCK_CHANGE, Records.wchgAction(v))
        assertEquals(CauseKind.EXPLOSION, Records.wchgCause(v))
        assertEquals(77, Records.wchgCausedBy(v))
        assertEquals(3, Records.wchgWorldId(v))
        assertEquals(1_700_000_000_123L, Records.wchgEpochMillis(v))
        assertEquals(9_000L, Records.sectionBaseSeq(v))
        assertEquals(built.positions.size, Records.sectionCount(v))

        assertEquals(-5 shl 4, Records.wchgX(v))
        assertEquals(2 shl 4, Records.wchgY(v))
        assertEquals(9 shl 4, Records.wchgZ(v))

        val seenIndex = ArrayList<Int>()
        val seenPacked = ArrayList<Int>()
        Records.forEachSectionPosition(v) { index, packed ->
            seenIndex += index
            seenPacked += packed
        }
        assertEquals(built.positions.toList(), seenPacked, "positions must come back ascending and complete")
        assertEquals(built.positions.indices.toList(), seenIndex)

        for (i in built.positions.indices) {
            assertEquals(built.before[i], Records.sectionBefore(v, i), "before at $i")
            assertEquals(built.after[i], Records.sectionAfter(v, i), "after at $i")
            assertEquals(i, Records.sectionIndexOf(v, built.positions[i]), "index of position $i")
        }
    }

    @Test
    fun `a sparse delta round-trips every position and state`() {
        val built = build(count = 30, stride = 7)
        assertTrue(!Records.sectionIsBitmap(built.value), "thirty positions are cheaper as a list")
        checkRoundTrip(built)
    }

    @Test
    fun `a dense delta round-trips every position and state`() {
        val built = build(count = 4096)
        assertTrue(Records.sectionIsBitmap(built.value), "a full section is cheaper as a bitmap")
        checkRoundTrip(built)
    }

    @Test
    fun `both encodings agree about which positions are absent`() {
        for (built in listOf(build(count = 30, stride = 7), build(count = 4096))) {
            val present = built.positions.toHashSet()
            for (packed in 0 until Records.SECTION_POSITIONS) {
                if (packed in present) continue
                assertEquals(-1, Records.sectionIndexOf(built.value, packed), "position $packed was not touched")
            }
        }
    }

    @Test
    fun `a palette wider than a byte still indexes correctly`() {
        val count = 700
        val positions = IntArray(count) { it }
        val before = IntArray(count) { 1000 + it }
        val after = IntArray(count) { 5000 + it }
        val bytes = Records.sectionDelta(
            ActionKind.BLOCK_CHANGE, CauseKind.PLAYER_ACTION, 1, 1, 0, 0, 0, 1L, 0L,
            positions, count, before, after,
        )
        val v = MemorySegment.ofArray(bytes)
        assertTrue(Records.sectionPaletteSize(v) > 256, "this test is pointless with a narrow palette")
        for (i in 0 until count) {
            assertEquals(before[i], Records.sectionBefore(v, i), "before at $i")
            assertEquals(after[i], Records.sectionAfter(v, i), "after at $i")
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
        assertArrayEquals(byteArrayOf(1, 2, 3), Records.sectionExtras(v, 2)!!.before)
        assertEquals(0, Records.sectionExtras(v, 2)!!.after.size)
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), Records.sectionExtras(v, 17)!!.after)
        assertNull(Records.sectionExtras(v, 3), "a position with no tile entity must not borrow its neighbour's")
        assertNull(Records.sectionExtras(v, 29))
    }

    @Test
    fun `a position packs and unpacks to itself`() {
        for (y in 0 until 16) {
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val packed = Records.packSectionPosition(x, y, z)
                    assertEquals(x, Records.sectionPositionX(packed))
                    assertEquals(y, Records.sectionPositionY(packed))
                    assertEquals(z, Records.sectionPositionZ(packed))
                    assertTrue(packed in 0 until Records.SECTION_POSITIONS)
                }
            }
        }
    }

    @Test
    fun `packing takes a block's offset within its section, whatever its world coordinates`() {
        assertEquals(
            Records.packSectionPosition(15, 15, 15),
            Records.packSectionPosition(-1, -1, -1),
            "-1 sits at offset 15 of the section below, not at -1 of this one",
        )
    }

    @Test
    fun `a falling block keeps its data and nothing else`() {
        val extras = EntityExtras.Falling(
            BlockDataKey("minecraft:sand")
        )
        val bytes = Records.entityExtras(extras)
        assertEquals(extras, Records.decodeEntityExtras(bytes))

        assertTrue(bytes.size < 32, "a falling block's extras came to ${bytes.size} bytes")
    }

    @Test
    fun `entity extras still round-trip the opaque kind beside the falling one`() {
        val opaque = EntityExtras.Opaque(ByteArray(40) { it.toByte() })
        assertEquals(opaque, Records.decodeEntityExtras(Records.entityExtras(opaque)))
        assertNull(Records.decodeEntityExtras(Records.entityExtras(null)))
    }

    @Test
    fun `a leash keeps its holder and the snapshot it wraps`() {
        val knot = UUID.fromString("6f3a1f5e-0c11-4f2a-9a3e-2f9e7c1b4d55")
        val leashed = EntityExtras.Leashed(
            knot,
            EntityExtras.Opaque(ByteArray(40) { it.toByte() }),
        )
        assertEquals(leashed, Records.decodeEntityExtras(Records.entityExtras(leashed)))
    }

    @Test
    fun `a leash survives with nothing wrapped in it`() {
        val bare = EntityExtras.Leashed(UUID.randomUUID(), null)
        assertEquals(bare, Records.decodeEntityExtras(Records.entityExtras(bare)))
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
        val decoded = Records.decodeEntityExtras(Records.entityExtras(both))
        assertEquals(both, decoded)
        assertEquals(boat, decoded.vehicle)
        assertEquals(knot, decoded.leashHolder)
        assertEquals(12, decoded.opaque?.bytes?.size)
    }

    @Test
    fun `a leashed shape round-trips through the payload the world log stores`() {
        val type = EntityTypeKey("minecraft:cow")
        val at = com.tracel.model.world.BlockPos(com.tracel.model.id.WorldId(java.util.UUID.randomUUID()), 1, 2, 3)
        val shape = EntityShape(
            type, 1.5, 2.0, 3.5, 90f, -12.5f,
            EntityExtras.Leashed(
                UUID.randomUUID(),
                EntityExtras.Opaque(ByteArray(8) { it.toByte() }),
            ),
        )
        assertEquals(shape, Records.decodeEntityShape(type, at, Records.entityShapePayload(shape)))
    }
}
