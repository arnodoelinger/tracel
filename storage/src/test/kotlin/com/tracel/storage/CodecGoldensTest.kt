package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Packed
import com.tracel.storage.codec.Records
import com.tracel.storage.ffm.Key
import java.lang.foreign.MemorySegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CodecGoldensTest {
    @Test
    fun `CauseKind ordinals are what is already on disk`() {
        assertEquals(
            listOf(
                "PLAYER_ACTION", "EXPLOSION", "HOPPER", "CRAFT", "BLOCK_BREAK", "ROLLBACK",
                "INVOLUTION", "ENTITY_ACTION", "WORLD", "PLUGIN", "UNKNOWN",
            ),
            CauseKind.entries.map { it.name },
        )
    }

    @Test
    fun `FlowKind ordinals are what is already on disk`() {
        assertEquals(
            listOf("MOVE", "MINT", "BURN", "TRANSFORM_IN", "TRANSFORM_OUT"),
            FlowKind.entries.map { it.name },
        )
    }

    @Test
    fun `SinkKind and SourceKind ordinals are what is already on disk`() {
        assertEquals(
            listOf("LAVA", "DESPAWN", "BURN_FUEL", "CRAFT_CONSUME", "COMMAND", "ROLLBACK_BURN", "UNTRACKED_GAP", "UNATTRIBUTED"),
            SinkKind.entries.map { it.name },
        )
        assertEquals(
            listOf("MOB_DROP", "CRAFT", "CREATIVE", "COMMAND", "WORLDGEN", "ROLLBACK_MINT", "UNTRACKED_GAP", "UNATTRIBUTED"),
            SourceKind.entries.map { it.name },
        )
    }

    @Test
    fun `the transaction record is a 24-byte header and 24 bytes per flow`() {
        assertEquals(1.toByte(), Records.VERSION)
        assertEquals(24, Records.transactionSize(0))
        assertEquals(48, Records.transactionSize(1))
        assertEquals(72, Records.transactionSize(2))
    }

    @Test
    fun `a two-flow transaction round-trips every packed field`() {
        val bytes = ByteArray(Records.transactionSize(2))
        val into = MemorySegment.ofArray(bytes)
        Records.writeTransactionHeader(into, CauseKind.EXPLOSION, 2, 77, 1234L, 1_700_000_000_000L)
        Records.writeFlow(into, 0, 5, 6, 7, FlowKind.BURN, 42L)
        Records.writeFlow(into, 1, 8, 9, 10, FlowKind.TRANSFORM_OUT, -3L)

        assertEquals(CauseKind.EXPLOSION, Records.txnCause(into))
        assertEquals(2, Records.txnFlowCount(into))
        assertEquals(77, Records.txnCausedBy(into))
        assertEquals(1234L, Records.txnId(into))
        assertEquals(1_700_000_000_000L, Records.txnEpochMillis(into))
        assertEquals(5, Records.flowItemKeyId(into, 0))
        assertEquals(FlowKind.BURN, Records.flowKind(into, 0))
        assertEquals(42L, Records.flowQuantity(into, 0))
        assertEquals(10, Records.flowDestination(into, 1))
        assertEquals(-3L, Records.flowQuantity(into, 1))
    }

    @Test
    fun `every holder variant round-trips its binary form`() {
        val world = WorldId(UUID(1, 2))
        val holders = listOf(
            HolderId.Block(world, -30_000_000, -64, 30_000_000),
            HolderId.PlacedBlock(world, 0, 320, 0),
            HolderId.Player(UUID(3, 4)),
            HolderId.Entity(UUID(5, 6)),
            HolderId.ItemEntity(UUID(7, 8)),
            HolderId.Escrow(RollbackJobId(Long.MAX_VALUE)),
            HolderId.Source(SourceKind.WORLDGEN),
            HolderId.Sink(SinkKind.ROLLBACK_BURN),
        )
        for (holder in holders) {
            assertEquals(holder, Packed.decodeHolder(MemorySegment.ofArray(Packed.holder(holder))))
        }
    }

    @Test
    fun `a block holder packs to 29 bytes, not sixty of ASCII`() {
        assertEquals(29, Packed.holder(HolderId.Block(WorldId(UUID(1, 2)), 1, 2, 3)).size)
        assertEquals(17, Packed.holder(HolderId.Player(UUID(1, 2))).size)
        assertEquals(2, Packed.holder(HolderId.Sink(SinkKind.LAVA)).size)
    }

    @Test
    fun `item keys round-trip with and without a decoration`() {
        for (key in listOf(ItemKey("minecraft:diamond"), ItemKey("minecraft:diamond_sword", ContentHash("cafebabe")))) {
            assertEquals(key, Packed.decodeItemKey(MemorySegment.ofArray(Packed.itemKey(key))))
        }
    }

    @Test
    fun `keys sort the way scans assume they do`() {
        // Unsigned lexicographic, so an id past 0x7F does not sort below one below it
        assertTrue(Key(Keys.lot(0x7FL)) < Key(Keys.lot(0x80L)))
        assertTrue(Key(Keys.lot(1L)) < Key(Keys.lot(Long.MAX_VALUE)))

        // Inverted sequences put the newest first in every secondary index
        assertTrue(Key(Keys.actor(1, 99)) < Key(Keys.actor(1, 98)))
        assertTrue(Key(Keys.time(500, 1)) < Key(Keys.time(400, 1)))

        // Negative coordinates sort below positive ones, which two's complement bytes do not
        assertTrue(Key(Keys.spatialChunkPrefix(1, -5, 0)) < Key(Keys.spatialChunkPrefix(1, 0, 0)))
        assertTrue(Key(Keys.spatialChunkPrefix(1, 0, -5)) < Key(Keys.spatialChunkPrefix(1, 0, 0)))

        // And a family never bleeds into the one next to it
        assertTrue(Key(Keys.lot(Long.MAX_VALUE)) < Key(Keys.place(0, 0, 0)))
    }

    @Test
    fun `y bins cover the whole build range in one unsigned byte`() {
        val bins = (-64..320 step 16).map { Keys.yBin(it).toInt() and 0xFF }
        assertEquals(bins, bins.sorted(), "a taller y must never bin below a shorter one")
        assertEquals(bins.distinct(), bins, "two different 16-block bands must not share a bin")
    }
}
