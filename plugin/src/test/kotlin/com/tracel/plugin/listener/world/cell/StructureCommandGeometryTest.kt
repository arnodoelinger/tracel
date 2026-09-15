package com.tracel.plugin.listener.world.cell

import com.tracel.plugin.util.ParsedBlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StructureCommandGeometryTest {
    @Test
    fun `a single-block box is just that one block`() {
        val box = fillBox(ParsedBlockPos(5, 64, 5), ParsedBlockPos(5, 64, 5), maxBlocks = DEFAULT_LIMIT)!!
        assertEquals(5 until 6, box.xBlocks())
        assertEquals(64 until 65, box.yBlocks())
        assertEquals(5 until 6, box.zBlocks())
        assertEquals(1.0, box.volume)
    }

    @Test
    fun `both corners are inclusive regardless of which one is smaller`() {
        val box = fillBox(ParsedBlockPos(2, 0, 0), ParsedBlockPos(0, 0, 0), maxBlocks = DEFAULT_LIMIT)!!
        assertEquals(0 until 3, box.xBlocks())
        assertEquals(0 until 1, box.yBlocks())
        assertEquals(0 until 1, box.zBlocks())
    }

    @Test
    fun `a box over the gamerule limit is refused rather than truncated`() {
        assertNull(fillBox(ParsedBlockPos(0, 0, 0), ParsedBlockPos(63, 63, 63), maxBlocks = DEFAULT_LIMIT))
    }

    @Test
    fun `a box past the vanilla default is captured when the gamerule allows it`() {
        assertNotNull(fillBox(ParsedBlockPos(0, 0, 0), ParsedBlockPos(63, 63, 63), maxBlocks = 262_144))
    }

    @Test
    fun `a lowered gamerule refuses a box the vanilla default would allow`() {
        assertNull(fillBox(ParsedBlockPos(0, 0, 0), ParsedBlockPos(9, 9, 9), maxBlocks = 100))
    }

    @Test
    fun `clone offsets the source volume to sit at the destination corner`() {
        val box = cloneDestBox(
            srcFrom = ParsedBlockPos(0, 0, 0),
            srcTo = ParsedBlockPos(1, 0, 0),
            dstOrigin = ParsedBlockPos(100, 5, -3),
            maxBlocks = DEFAULT_LIMIT,
        )!!
        assertEquals(100 until 102, box.xBlocks())
        assertEquals(5 until 6, box.yBlocks())
        assertEquals(-3 until -2, box.zBlocks())
    }

    @Test
    fun `clone destination volume is refused past the same limit as fill`() {
        assertNull(
            cloneDestBox(
                ParsedBlockPos(0, 0, 0),
                ParsedBlockPos(63, 63, 63),
                ParsedBlockPos(0, 0, 0),
                maxBlocks = DEFAULT_LIMIT,
            ),
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 32_768
    }
}
