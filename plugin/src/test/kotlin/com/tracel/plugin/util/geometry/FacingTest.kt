package com.tracel.plugin.util.geometry

import com.tracel.plugin.util.facingFromPose
import com.tracel.plugin.util.facingFromYaw
import org.bukkit.block.BlockFace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FacingTest {
    @Test
    fun `a hanging entity's yaw names the wall it hangs on`() {
        assertEquals(BlockFace.SOUTH, facingFromYaw(0f))
        assertEquals(BlockFace.WEST, facingFromYaw(90f))
        assertEquals(BlockFace.NORTH, facingFromYaw(180f))
        assertEquals(BlockFace.EAST, facingFromYaw(270f))
    }

    @Test
    fun `a yaw that has been through a float round trip still lands on the right wall`() {
        assertEquals(BlockFace.WEST, facingFromYaw(89.9f))
        assertEquals(BlockFace.WEST, facingFromYaw(90.1f))
        assertEquals(BlockFace.SOUTH, facingFromYaw(359.9f))
    }

    @Test
    fun `negative and over-full-turn yaws normalize`() {
        assertEquals(BlockFace.EAST, facingFromYaw(-90f))
        assertEquals(BlockFace.NORTH, facingFromYaw(-180f))
        assertEquals(BlockFace.WEST, facingFromYaw(450f))
    }

    @Test
    fun `pitch names a floor or ceiling frame, not a wall`() {
        assertEquals(BlockFace.UP, facingFromPose(0f, -90f))
        assertEquals(BlockFace.UP, facingFromPose(180f, -45f))
        assertEquals(BlockFace.DOWN, facingFromPose(0f, 90f))
        assertEquals(BlockFace.DOWN, facingFromPose(90f, 45f))
        assertEquals(BlockFace.SOUTH, facingFromPose(0f, 0f))
        assertEquals(BlockFace.WEST, facingFromPose(90f, 0f))
    }
}
