package com.tracel.storage.codec

import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.model.log.LogKind
import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.ffm.Key
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import java.util.*

class CodecGoldensTest {
    @Test
    fun `CauseKind ordinals are what is already on disk`() {
        assertEquals(
            listOf(
                "PLAYER_ACTION", "EXPLOSION", "MACHINE", "CRAFT", "BLOCK_BREAK", "ROLLBACK",
                "INVOLUTION", "ENTITY_ACTION", "NATURAL", "PLUGIN", "UNKNOWN", "WEAR", "PROJECTILE",
                "LIGHTNING", "FIRE", "FLUID", "GROWTH", "HOPPER", "DISPENSER", "DROPPER",
            ),
            CauseKind.entries.map { it.name },
        )
    }

    @Test
    fun `ActionKind ordinals are what is already on disk`() {
        assertEquals(
            listOf(
                "BLOCK_PLACE", "BLOCK_BREAK", "BLOCK_CHANGE", "SIGN_EDIT",
                "ENTITY_SPAWN", "ENTITY_REMOVE", "ENTITY_CHANGE", "BLOCK_CLICK", "BLOCK_GROW",
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
                "HAZARD", "DESPAWN", "BURN_FUEL", "CRAFT_CONSUME", "COMMAND", "ROLLBACK_BURN",
                "UNTRACKED_GAP", "UNATTRIBUTED", "CREATIVE",
            ),
            SinkKind.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "MOB_DROP",
                "CRAFT",
                "CREATIVE",
                "COMMAND",
                "WORLDGEN",
                "ROLLBACK_MINT",
                "UNTRACKED_GAP",
                "UNATTRIBUTED",
                "PLUGIN",
            ),
            SourceKind.entries.map { it.name },
        )
    }

    @Test
    fun `the transaction record is a 40-byte header and 24 bytes per flow`() {
        assertEquals(1.toByte(), Scalar.VERSION, "the codec version is pinned; bumping it means wiping the store")
        assertEquals(40, Txn.size(0))
        assertEquals(64, Txn.size(1))
        assertEquals(88, Txn.size(2))
    }

    @Test
    fun `a two-flow transaction round-trips every packed field`() {
        val bytes = ByteArray(Txn.size(2))
        val into = MemorySegment.ofArray(bytes)
        Txn.writeHeader(into, CauseKind.EXPLOSION, 2, 77, 1234L, 1_700_000_000_000L, 3, -140, 71, 208)
        Txn.writeFlow(into, 0, 5, 6, 7, FlowKind.BURN, 42L)
        Txn.writeFlow(into, 1, 8, 9, 10, FlowKind.TRANSFORM_OUT, -3L)

        assertEquals(3, Txn.world[into])
        assertEquals(-140, Txn.x[into])
        assertEquals(71, Txn.y[into])
        assertEquals(208, Txn.z[into])

        assertEquals(CauseKind.EXPLOSION, Txn.cause[into])
        assertEquals(2, Txn.flowCount[into])
        assertEquals(77, Txn.causedBy[into])
        assertEquals(1234L, Txn.id[into])
        assertEquals(1_700_000_000_000L, Txn.epochMillis[into])
        assertEquals(5, Txn.item[into, 0])
        assertEquals(FlowKind.BURN, Txn.kind[into, 0])
        assertEquals(42L, Txn.quantity[into, 0])
        assertEquals(10, Txn.destination[into, 1])
        assertEquals(-3L, Txn.quantity[into, 1])
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
            HolderId.Plugin("Arena"),
            HolderId.Custom("backpacks:backpack", "6767-ёж"),
        )
        for (holder in holders) {
            assertEquals(holder, Packed.decodeHolder(MemorySegment.ofArray(Packed.holder(holder))))
        }
    }

    @Test
    fun `a block holder packs to 29 bytes, not sixty of ASCII`() {
        assertEquals(29, Packed.holder(HolderId.Block(WorldId(UUID(1, 2)), 1, 2, 3)).size)
        assertEquals(17, Packed.holder(HolderId.Player(UUID(1, 2))).size)
        assertEquals(2, Packed.holder(HolderId.Sink(SinkKind.HAZARD)).size)
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
            BlockChange.of(
                ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, 77, 3,
                -30_000_000, -64, 30_000_000, 1_700_000_000_000L, 11, 0, before, after,
            )
        )

        assertEquals(Scalar.VERSION, Wchg.version[record])
        assertEquals(Wchg.BLOCK, Wchg.kind[record])
        assertEquals(ActionKind.BLOCK_BREAK, Wchg.action[record])
        assertEquals(CauseKind.EXPLOSION, Wchg.cause[record])
        assertEquals(77, Wchg.causedBy[record])
        assertEquals(3, Wchg.world[record])
        assertEquals(-30_000_000, Wchg.x[record])
        assertEquals(-64, Wchg.y[record])
        assertEquals(30_000_000, Wchg.z[record])
        assertEquals(1_700_000_000_000L, Wchg.epochMillis[record])
        assertEquals(11, BlockChange.before[record])
        assertEquals(0, BlockChange.after[record])
        assertArrayEquals(before, BlockChange.beforeExtras[record])
        assertArrayEquals(after, BlockChange.afterExtras[record])
    }

    @Test
    fun `an entity change round-trips every packed field`() {
        val entity = UUID(12, 34)
        val after = byteArrayOf(7, 7, 7, 7)
        val record = MemorySegment.ofArray(
            EntityChange.of(
                ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, 5, 1,
                10, 70, -10, 42L, 9, entity, ByteArray(0), after,
            )
        )

        assertEquals(Wchg.ENTITY, Wchg.kind[record])
        assertEquals(ActionKind.ENTITY_SPAWN, Wchg.action[record])
        assertEquals(9, EntityChange.type[record])
        assertEquals(entity, EntityChange.entity[record])
        assertArrayEquals(ByteArray(0), EntityChange.beforeExtras[record])
        assertArrayEquals(after, EntityChange.afterExtras[record])
    }

    @Test
    fun `a shared index value says which log its sequence belongs to`() {
        for (kind in LogKind.entries) {
            assertEquals(kind, Index.kind(MemorySegment.ofArray(Index.bare(kind))))
        }
        assertEquals(1, Index.bare(LogKind.WORLD).size, "one byte per index entry, not four")
        val spatial = Index.at(LogKind.WORLD, 1_700_000_000_000L, CauseKind.PLAYER_ACTION, 3, 64, -5)
        val packed = MemorySegment.ofArray(spatial)
        assertEquals(22, spatial.size)
        assertEquals(LogKind.WORLD, Index.kind(packed))
        assertEquals(1_700_000_000_000L, Index.millis(packed))
        assertEquals(CauseKind.PLAYER_ACTION, Index.cause(packed))
        assertEquals(3, Index.x(packed))
        assertEquals(64, Index.y(packed))
        assertEquals(-5, Index.z(packed))
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
        assertTrue(Key(Tag.LOT[0x7FL]) < Key(Tag.LOT[0x80L]))
        assertTrue(Key(Tag.LOT[1L]) < Key(Tag.LOT[Long.MAX_VALUE]))

        assertTrue(Key(Tag.ACTOR[1, 99]) < Key(Tag.ACTOR[1, 98]))
        assertTrue(Key(Tag.TIME[500, 1]) < Key(Tag.TIME[400, 1]))

        assertTrue(Key(Tag.SPATIAL[1, -5, 0]) < Key(Tag.SPATIAL[1, 0, 0]))
        assertTrue(Key(Tag.SPATIAL[1, 0, -5]) < Key(Tag.SPATIAL[1, 0, 0]))
        assertTrue(Key(Tag.SPATIAL[1, 0, 0, 5_000, Keys.yBin(70), 1]) < Key(Tag.SPATIAL[1, 0, 0, 4_000, Keys.yBin(70), 2]))
        assertTrue(Key(Tag.SPATIAL[1, 0, 9]) < Key(Tag.SPATIAL[1, 1, -9]))

        assertTrue(Key(Tag.LOT[Long.MAX_VALUE]) < Key(Tag.PACK_AT[0, 0, 0]))
        assertTrue(Key(Tag.INTERN_REVERSE[Keys.NS_ENTITY_TYPE, ByteArray(64) { -1 }]) < Key(Tag.WCHG[0]))
        assertTrue(Key(Tag.WCHG[Long.MAX_VALUE]) < Key(Tag.WCHG_AT[0, 0, 0, 0, 0]))
        assertTrue(Key(Tag.TXN_LOT[0, 0, 0]) < Key(Tag.BLOCK_LEASE[0, 0, 0, 0]))
        assertTrue(Key(Tag.BLOCK_LEASE[0, 0, 0, 0]) < Key(Tag.RB_STRUCT[0, 0]))
    }

    @Test
    fun `a block history scan reads newest first and never leaves its own coordinate`() {
        assertTrue(Key(Tag.WCHG_AT[1, 10, 70, -3, 99]) < Key(Tag.WCHG_AT[1, 10, 70, -3, 98]))

        assertTrue(Key(Tag.WCHG_AT[1, -1, 0, 0]) < Key(Tag.WCHG_AT[1, 0, 0, 0]))
        assertTrue(Key(Tag.WCHG_AT[1, 0, -64, 0]) < Key(Tag.WCHG_AT[1, 0, 0, 0]))
        assertTrue(Key(Tag.WCHG_AT[1, 0, 0, -1]) < Key(Tag.WCHG_AT[1, 0, 0, 0]))

        val here = Tag.WCHG_AT[1, 10, 70, -3]
        assertTrue(Key(Tag.WCHG_AT[1, 10, 70, -3, Long.MAX_VALUE]).startsWith(here))
        assertFalse(Key(Tag.WCHG_AT[1, 10, 70, -2, 0]).startsWith(here))
        assertFalse(Key(Tag.WCHG_AT[1, 11, 70, -3, 0]).startsWith(here))
    }

    @Test
    fun `a transaction's lots scan in flow order and stay inside their transaction`() {
        val prefix = Tag.TXN_LOT[7]
        assertTrue(Key(Tag.TXN_LOT[7, 0, 500]) < Key(Tag.TXN_LOT[7, 1, 1]), "flow order, not lot order")
        assertTrue(Key(Tag.TXN_LOT[7, 0, 1]) < Key(Tag.TXN_LOT[7, 0, 2]))
        assertTrue(Key(Tag.TXN_LOT[7, 3, 9]).startsWith(prefix))
        assertFalse(Key(Tag.TXN_LOT[8, 0, 0]).startsWith(prefix))
    }

    @Test
    fun `y bins cover the whole build range in one unsigned byte`() {
        val bins = (-64..320 step 16).map { Keys.yBin(it).toInt() and 0xFF }
        assertEquals(bins, bins.sorted(), "a taller y must never bin below a shorter one")
        assertEquals(bins.distinct(), bins, "two different 16-block bands must not share a bin")
    }
}
