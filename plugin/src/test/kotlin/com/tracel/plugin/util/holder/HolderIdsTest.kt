package com.tracel.plugin.util.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

private val WORLD = WorldId(UUID(0L, 1L))
private val WHO = UUID(0L, 2L)

class HolderIdsTest {
    @Test
    fun `only the block-shaped holders carry coordinates`() {
        assertEquals(BlockPos(WORLD, 1, 2, 3), HolderId.Block(WORLD, 1, 2, 3).blockPos())
        assertEquals(BlockPos(WORLD, 1, 2, 3), HolderId.PlacedBlock(WORLD, 1, 2, 3).blockPos())

        assertNull(HolderId.Player(WHO).blockPos())
        assertFalse(HolderId.Player(WHO).carriesCoordinates())

        assertNull(HolderId.Entity(WHO).blockPos())
        assertNull(HolderId.Source(SourceKind.WORLDGEN).blockPos())
        assertNull(HolderId.Sink(SinkKind.DESPAWN).blockPos())
    }

    @Test
    fun `an entity-named holder is one that can simply stop existing, and a player cannot`() {
        assertTrue(HolderId.Entity(WHO).namedByEntity())
        assertTrue(HolderId.PlacedEntity(WHO).namedByEntity())
        assertTrue(HolderId.ItemEntity(WHO).namedByEntity())

        assertFalse(HolderId.Player(WHO).namedByEntity())
        assertFalse(HolderId.Block(WORLD, 0, 0, 0).namedByEntity())
    }

    @Test
    fun `entityUuid names only the uuids that can stop existing`() {
        assertEquals(WHO, HolderId.Entity(WHO).entityUuid())
        assertEquals(WHO, HolderId.PlacedEntity(WHO).entityUuid())

        assertNull(HolderId.Player(WHO).entityUuid())
        assertNull(HolderId.Block(WORLD, 0, 0, 0).entityUuid())
    }
}
