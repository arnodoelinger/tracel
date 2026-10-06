package com.tracel.plugin.util.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaintingAnchorTest {
    private val anchor = Triple(10.5, 64.5, 20.5)

    private fun position(tall: Int) = 64.5 + if (tall % 2 == 0) 0.5 else 0.0

    @Test
    fun `an odd-sized painting sits dead centre and needs no correction`() {
        assertEquals(anchor, anchorPoint(10.5, position(1), 20.5, Facing.SOUTH, 1, 1))
        assertEquals(anchor, anchorPoint(10.5, position(3), 20.5, Facing.SOUTH, 3, 3))
    }

    @Test
    fun `an even-height painting is half a block up and must not be floored where it sits`() {
        assertEquals(anchor, anchorPoint(10.5, position(2), 20.5, Facing.SOUTH, 1, 2))
        assertEquals(anchor, anchorPoint(10.5, position(4), 20.5, Facing.SOUTH, 1, 4))
    }

    @Test
    fun `an even-width painting is half a block across the wall, on whichever side the facing puts it`() {
        assertEquals(anchor, anchorPoint(11.0, position(4), 20.5, Facing.SOUTH, 4, 4))
        assertEquals(anchor, anchorPoint(10.0, position(4), 20.5, Facing.NORTH, 4, 4))
        assertEquals(anchor, anchorPoint(10.5, position(4), 20.0, Facing.EAST, 4, 4))
        assertEquals(anchor, anchorPoint(10.5, position(4), 21.0, Facing.WEST, 4, 4))
    }

    @Test
    fun `a frame is one by one and comes back untouched whichever way it faces`() {
        for (face in listOf(Facing.NORTH, Facing.SOUTH, Facing.EAST, Facing.WEST, Facing.UP)) {
            assertEquals(anchor, anchorPoint(10.5, 64.5, 20.5, face, 1, 1), "a frame on $face")
        }
    }
}
