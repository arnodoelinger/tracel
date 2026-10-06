package com.tracel.plugin.integration.worldedit

import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.specifics.block.AIR
import com.tracel.tests.support.Fixtures
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EditBufferTest {
    private val buffer = EditBuffer(Fixtures.world)
    private val stone = BlockShape(BlockDataKey("minecraft:stone"))
    private val dirt = BlockShape(BlockDataKey("minecraft:dirt"))

    @Test
    fun `a cell written twice is one change from the first shape to the last`() {
        buffer.note(1, 64, 1, stone, dirt)
        buffer.note(1, 64, 1, dirt, AIR)

        val edit = buffer.drain().getValue(ActionKind.BLOCK_BREAK).single()
        assertEquals(stone, edit.before)
        assertEquals(AIR, edit.after)
    }

    @Test
    fun `a cell that ends where it started is not a change`() {
        buffer.note(1, 64, 1, stone, dirt)
        buffer.note(1, 64, 1, dirt, stone)

        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun `edits are grouped by what they did to their cell`() {
        buffer.note(0, 64, 0, AIR, stone)
        buffer.note(1, 64, 0, stone, AIR)
        buffer.note(2, 64, 0, stone, dirt)

        val grouped = buffer.drain()
        assertEquals(setOf(ActionKind.BLOCK_PLACE, ActionKind.BLOCK_BREAK, ActionKind.BLOCK_CHANGE), grouped.keys)
        assertEquals(0, grouped.getValue(ActionKind.BLOCK_PLACE).single().at.x)
        assertEquals(1, grouped.getValue(ActionKind.BLOCK_BREAK).single().at.x)
        assertEquals(2, grouped.getValue(ActionKind.BLOCK_CHANGE).single().at.x)
    }

    @Test
    fun `one kind of air replaced by another is nothing`() {
        buffer.note(0, 64, 0, AIR, BlockShape(BlockDataKey("minecraft:cave_air")))

        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun `draining empties the buffer and the next note says it was empty`() {
        assertTrue(buffer.note(0, 64, 0, stone, dirt))
        assertFalse(buffer.note(1, 64, 0, stone, dirt))
        assertEquals(2, buffer.size)

        buffer.drain()

        assertEquals(0, buffer.size)
        assertTrue(buffer.note(2, 64, 0, stone, dirt))
    }

    @Test
    fun `cells that differ only in sign or height do not collide`() {
        val seen = HashSet<Long>()
        for (x in listOf(-1, 0, 1)) for (y in listOf(-64, 0, 319)) for (z in listOf(-1, 0, 1)) {
            assertTrue(seen.add(EditBuffer.packed(x, y, z)), "($x, $y, $z) collided")
        }
    }
}
