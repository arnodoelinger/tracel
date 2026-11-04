package com.tracel.plugin.util.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FacingTest {
    @Test
    fun `a hanging entity's yaw names the wall it hangs on`() {
        assertEquals(Facing.SOUTH, facingFromYaw(0f))
        assertEquals(Facing.WEST, facingFromYaw(90f))
        assertEquals(Facing.NORTH, facingFromYaw(180f))
        assertEquals(Facing.EAST, facingFromYaw(270f))
    }

    @Test
    fun `a yaw that has been through a float round trip still lands on the right wall`() {
        assertEquals(Facing.WEST, facingFromYaw(89.9f))
        assertEquals(Facing.WEST, facingFromYaw(90.1f))
        assertEquals(Facing.SOUTH, facingFromYaw(359.9f))
    }

    @Test
    fun `negative and over-full-turn yaws normalize`() {
        assertEquals(Facing.EAST, facingFromYaw(-90f))
        assertEquals(Facing.NORTH, facingFromYaw(-180f))
        assertEquals(Facing.WEST, facingFromYaw(450f))
    }

    @Test
    fun `pitch names a floor or ceiling frame, not a wall`() {
        assertEquals(Facing.UP, facingFromPose(0f, -90f))
        assertEquals(Facing.UP, facingFromPose(180f, -45f))
        assertEquals(Facing.DOWN, facingFromPose(0f, 90f))
        assertEquals(Facing.DOWN, facingFromPose(90f, 45f))
        assertEquals(Facing.SOUTH, facingFromPose(0f, 0f))
        assertEquals(Facing.WEST, facingFromPose(90f, 0f))
    }
}
