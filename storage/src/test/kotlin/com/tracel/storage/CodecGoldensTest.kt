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
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockDataKey
import com.tracel.model.world.EntityTypeKey
import com.tracel.model.world.LogKind
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Packed
import com.tracel.storage.codec.Records
import com.tracel.storage.ffm.Key
import java.lang.foreign.MemorySegment
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun `ActionKind ordinals are what is already on disk`() {
        assertEquals(
            listOf(
                "BLOCK_PLACE", "BLOCK_BREAK", "BLOCK_CHANGE", "SIGN_EDIT",
                "ENTITY_SPAWN", "ENTITY_REMOVE", "ENTITY_CHANGE",
            ),
            ActionKind.entries.map { it.name },
        )
    }

    @Test
    fun `LogKind ordinals are what is already on disk`() {
        assertEquals(listOf("TRANSACTION", "WORLD"), LogKind.entries.map { it.name })
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
            listOf(
                "LAVA", "DESPAWN", "BURN_FUEL", "CRAFT_CONSUME", "COMMAND", "ROLLBACK_BURN",
                "UNTRACKED_GAP", "UNATTRIBUTED", "CREATIVE",
            ),
            SinkKind.entries.map { it.name },
        )
        assertEquals(
            listOf("MOB_DROP", "CRAFT", "CREATIVE", "COMMAND", "WORLDGEN", "ROLLBACK_MINT", "UNTRACKED_GAP", "UNATTRIBUTED"),
            SourceKind.entries.map { it.name },
        )
    }

    @Test
    fun `the transaction record is a 40-byte header and 24 bytes per flow`() {
        assertEquals(1.toByte(), Records.VERSION, "the codec version is pinned; bumping it means wiping the store")
        assertEquals(40, Records.transactionSize(0))
        assertEquals(64, Records.transactionSize(1))
        assertEquals(88, Records.transactionSize(2))
    }

    @Test
    fun `a two-flow transaction round-trips every packed field`() {
        val bytes = ByteArray(Records.transactionSize(2))
        val into = MemorySegment.ofArray(bytes)
        Records.writeTransactionHeader(into, CauseKind.EXPLOSION, 2, 77, 1234L, 1_700_000_000_000L, 3, -140, 71, 208)
        Records.writeFlow(into, 0, 5, 6, 7, FlowKind.BURN, 42L)
        Records.writeFlow(into, 1, 8, 9, 10, FlowKind.TRANSFORM_OUT, -3L)

        // The position a transaction between two holders with no coordinates of their own carries
        assertEquals(3, Records.txnWorldId(into))
        assertEquals(-140, Records.txnX(into))
        assertEquals(71, Records.txnY(into))
        assertEquals(208, Records.txnZ(into))

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
            HolderId.PlacedEntity(UUID(9, 10)),
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
    fun `a block change round-trips every packed field`() {
        val before = byteArrayOf(1, 2, 3)
        val after = byteArrayOf(4, 5)
        val record = MemorySegment.ofArray(
            Records.blockChange(
                ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, 77, 3,
                -30_000_000, -64, 30_000_000, 1_700_000_000_000L, 11, 0, before, after,
            )
        )

        assertEquals(Records.VERSION, Records.wchgVersion(record))
        assertEquals(Records.CHANGE_BLOCK, Records.wchgKind(record))
        assertEquals(ActionKind.BLOCK_BREAK, Records.wchgAction(record))
        assertEquals(CauseKind.EXPLOSION, Records.wchgCause(record))
        assertEquals(77, Records.wchgCausedBy(record))
        assertEquals(3, Records.wchgWorldId(record))
        assertEquals(-30_000_000, Records.wchgX(record))
        assertEquals(-64, Records.wchgY(record))
        assertEquals(30_000_000, Records.wchgZ(record))
        assertEquals(1_700_000_000_000L, Records.wchgEpochMillis(record))
        assertEquals(11, Records.blockChangeBefore(record))
        assertEquals(0, Records.blockChangeAfter(record))
        assertArrayEquals(before, Records.blockChangeBeforeExtras(record))
        assertArrayEquals(after, Records.blockChangeAfterExtras(record))
    }

    @Test
    fun `an entity change round-trips every packed field`() {
        val entity = UUID(12, 34)
        val after = byteArrayOf(7, 7, 7, 7)
        val record = MemorySegment.ofArray(
            Records.entityChange(
                ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, 5, 1,
                10, 70, -10, 42L, 9, entity, ByteArray(0), after,
            )
        )

        assertEquals(Records.CHANGE_ENTITY, Records.wchgKind(record))
        assertEquals(ActionKind.ENTITY_SPAWN, Records.wchgAction(record))
        assertEquals(9, Records.entityChangeTypeId(record))
        assertEquals(entity, Records.entityChangeUuid(record))
        assertArrayEquals(ByteArray(0), Records.entityChangeBeforeExtras(record))
        assertArrayEquals(after, Records.entityChangeAfterExtras(record))
    }

    @Test
    fun `a shared index value says which log its sequence belongs to`() {
        for (kind in LogKind.entries) {
            assertEquals(kind, Records.asLogKind(MemorySegment.ofArray(Records.logKind(kind))))
        }
        assertEquals(1, Records.logKind(LogKind.WORLD).size, "one byte per index entry, not four")
        val spatial = Records.logKind(LogKind.WORLD, 1_700_000_000_000L, CauseKind.PLAYER_ACTION, 3, 64, -5)
        val packed = MemorySegment.ofArray(spatial)
        assertEquals(22, spatial.size)
        assertEquals(LogKind.WORLD, Records.asLogKind(packed))
        assertEquals(1_700_000_000_000L, Records.logKindMillis(packed))
        assertEquals(CauseKind.PLAYER_ACTION, Records.logKindCause(packed))
        assertEquals(3, Records.logKindX(packed))
        assertEquals(64, Records.logKindY(packed))
        assertEquals(-5, Records.logKindZ(packed))
    }

    @Test
    fun `interned text round-trips, empty and long alike`() {
        for (text in listOf("", "minecraft:oak_stairs[facing=east,half=bottom,waterlogged=false]")) {
            val key = BlockDataKey(text)
            assertEquals(key, Packed.decodeBlockData(MemorySegment.ofArray(Packed.blockData(key))))
        }
        val type = EntityTypeKey("minecraft:chest_boat")
        assertEquals(type, Packed.decodeEntityType(MemorySegment.ofArray(Packed.entityType(type))))
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
        // A chunk's rows are newest first, so `t:10m` is a seek and a short walk, not an hour
        assertTrue(Key(Keys.spatial(1, 0, 0, 70, 1, 5_000)) < Key(Keys.spatial(1, 0, 0, 70, 2, 4_000)))
        // z runs inside x, so one column of a region is one contiguous cursor
        assertTrue(Key(Keys.spatialChunkPrefix(1, 0, 9)) < Key(Keys.spatialChunkPrefix(1, 1, -9)))

        // And a family never bleeds into the one next to it
        assertTrue(Key(Keys.lot(Long.MAX_VALUE)) < Key(Keys.place(0, 0, 0)))
        assertTrue(Key(Keys.internReverse(Keys.NS_ENTITY_TYPE, ByteArray(64) { -1 })) < Key(Keys.wchg(0)))
        assertTrue(Key(Keys.wchg(Long.MAX_VALUE)) < Key(Keys.wchgAt(0, 0, 0, 0, 0)))
        assertTrue(Key(Keys.txnLot(0, 0, 0)) < Key(Keys.blockLease(0, 0, 0, 0)))
        assertTrue(Key(Keys.blockLease(0, 0, 0, 0)) < Key(Keys.rbStruct(0, 0)))
    }

    @Test
    fun `a block history scan reads newest first and never leaves its own coordinate`() {
        // Newest first within one block, the same inversion every other index uses
        assertTrue(Key(Keys.wchgAt(1, 10, 70, -3, 99)) < Key(Keys.wchgAt(1, 10, 70, -3, 98)))

        // Negative coordinates below positive ones, on every axis
        assertTrue(Key(Keys.wchgAtPrefix(1, -1, 0, 0)) < Key(Keys.wchgAtPrefix(1, 0, 0, 0)))
        assertTrue(Key(Keys.wchgAtPrefix(1, 0, -64, 0)) < Key(Keys.wchgAtPrefix(1, 0, 0, 0)))
        assertTrue(Key(Keys.wchgAtPrefix(1, 0, 0, -1)) < Key(Keys.wchgAtPrefix(1, 0, 0, 0)))

        // A prefix scan of one block stops at the neighbor, whichever axis moved
        val here = Keys.wchgAtPrefix(1, 10, 70, -3)
        assertTrue(Key(Keys.wchgAt(1, 10, 70, -3, Long.MAX_VALUE)).startsWith(here))
        assertFalse(Key(Keys.wchgAt(1, 10, 70, -2, 0)).startsWith(here))
        assertFalse(Key(Keys.wchgAt(1, 11, 70, -3, 0)).startsWith(here))
    }

    @Test
    fun `a transaction's lots scan in flow order and stay inside their transaction`() {
        val prefix = Keys.txnLotPrefix(7)
        assertTrue(Key(Keys.txnLot(7, 0, 500)) < Key(Keys.txnLot(7, 1, 1)), "flow order, not lot order")
        assertTrue(Key(Keys.txnLot(7, 0, 1)) < Key(Keys.txnLot(7, 0, 2)))
        assertTrue(Key(Keys.txnLot(7, 3, 9)).startsWith(prefix))
        assertFalse(Key(Keys.txnLot(8, 0, 0)).startsWith(prefix))
    }

    @Test
    fun `y bins cover the whole build range in one unsigned byte`() {
        val bins = (-64..320 step 16).map { Keys.yBin(it).toInt() and 0xFF }
        assertEquals(bins, bins.sorted(), "a taller y must never bin below a shorter one")
        assertEquals(bins.distinct(), bins, "two different 16-block bands must not share a bin")
    }
}
