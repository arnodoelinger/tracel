package com.tracel.plugin.util.geometry

import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.plugin.util.regionKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

private val WORLD = WorldId(java.util.UUID(0L, 1L))
private val OTHER_WORLD = WorldId(java.util.UUID(0L, 9L))

class ChunksTest {
    @Test
    fun `one chunk is one group, whatever the height`() {
        val here = BlockPos(WORLD, 1, 70, 2)
        assertEquals(here.regionKey(), BlockPos(WORLD, 15, -60, 15).regionKey())
        assertNotEquals(here.regionKey(), BlockPos(WORLD, 16, 70, 2).regionKey())
        assertNotEquals(here.regionKey(), BlockPos(WORLD, 1, 70, 16).regionKey())
        assertNotEquals(here.regionKey(), BlockPos(OTHER_WORLD, 1, 70, 2).regionKey())
    }

    @Test
    fun `negative coordinates land in the chunk below zero, not the one above`() {
        assertEquals(BlockPos(WORLD, -1, 0, -1).regionKey(), BlockPos(WORLD, -16, 0, -16).regionKey())
        assertNotEquals(BlockPos(WORLD, -1, 0, -1).regionKey(), BlockPos(WORLD, 0, 0, 0).regionKey())
    }

    @Test
    fun `a holder with no coordinates is its own group`() {
        val entity = HolderId.Entity(java.util.UUID(0L, 2L))
        assertEquals(entity, entity.regionKey())
        assertEquals(
            HolderId.Block(WORLD, 1, 70, 2).regionKey(),
            HolderId.PlacedBlock(WORLD, 15, 70, 15).regionKey(),
        )
    }
}
