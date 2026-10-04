package com.tracel.tests.rollback

import com.tracel.engine.rollback.structure.groupByChunk
import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

class ChunkGroupsTest {
    private val overworld = WorldId(UUID(0L, 1L))
    private val nether = WorldId(UUID(0L, 2L))

    private fun at(world: WorldId, x: Int, z: Int) = BlockPos(world, x, 64, z)

    @Test
    fun `items of one chunk are one group, in the order they came`() {
        val items = listOf(at(overworld, 1, 1), at(overworld, 15, 0), at(overworld, 0, 15))

        assertEquals(listOf(items), groupByChunk(items) { it })
    }

    @Test
    fun `a chunk met again later joins its first group, and groups keep the order their chunk was first met`() {
        val a1 = at(overworld, 0, 0)
        val b = at(overworld, 16, 0)
        val a2 = at(overworld, 5, 5)
        val c = at(overworld, -1, -1)

        assertEquals(listOf(listOf(a1, a2), listOf(b), listOf(c)), groupByChunk(listOf(a1, b, a2, c)) { it })
    }

    @Test
    fun `the same chunk coordinates in two worlds are two groups`() {
        val over = at(overworld, 3, 3)
        val under = at(nether, 3, 3)

        assertEquals(listOf(listOf(over), listOf(under)), groupByChunk(listOf(over, under)) { it })
    }

    @Test
    fun `negative coordinates fall in the chunk below zero, not in chunk zero`() {
        val below = at(overworld, -1, 0)
        val zero = at(overworld, 0, 0)

        assertEquals(listOf(listOf(below), listOf(zero)), groupByChunk(listOf(below, zero)) { it })
    }

    @Test
    fun `nothing is nothing`() {
        assertEquals(emptyList<List<BlockPos>>(), groupByChunk(emptyList<BlockPos>()) { it })
    }
}
