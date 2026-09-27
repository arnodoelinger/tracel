package com.tracel.tests.rollback

import com.tracel.engine.rollback.structure.structuralPartnerOf
import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.*

class StructurePairingTest {
    private val world = WorldId(UUID(0L, 1L))
    private val here = BlockPos(world, 10, 70, -3)

    private fun shape(state: String) = BlockShape(BlockDataKey("minecraft:$state"))

    @Test
    fun `a chest's left half points at its right half, across facing`() {
        assertEquals(here.copy(x = 11), structuralPartnerOf(here, shape("chest[facing=north,type=left]")))
        assertEquals(here.copy(x = 9), structuralPartnerOf(here, shape("chest[facing=north,type=right]")))
        assertEquals(here.copy(z = -2), structuralPartnerOf(here, shape("chest[facing=east,type=left]")))
        assertEquals(here.copy(z = -4), structuralPartnerOf(here, shape("chest[facing=east,type=right]")))
    }

    @Test
    fun `trapped chests pair the same way, and a single chest points at nothing`() {
        assertEquals(here.copy(x = 11), structuralPartnerOf(here, shape("trapped_chest[facing=north,type=left]")))
        assertNull(structuralPartnerOf(here, shape("chest[facing=north,type=single]")))
    }

    @Test
    fun `a bed's head and foot point at each other, along facing`() {
        assertEquals(here.copy(z = -4), structuralPartnerOf(here, shape("red_bed[facing=south,part=head]")))
        assertEquals(here.copy(z = -2), structuralPartnerOf(here, shape("red_bed[facing=south,part=foot]")))
    }

    @Test
    fun `a door's two halves point straight up and down`() {
        assertEquals(here.copy(y = 69), structuralPartnerOf(here, shape("oak_door[half=upper,facing=north]")))
        assertEquals(here.copy(y = 71), structuralPartnerOf(here, shape("oak_door[half=lower,facing=north]")))
    }

    @Test
    fun `a tall plant's two halves point straight up and down too`() {
        assertEquals(here.copy(y = 69), structuralPartnerOf(here, shape("sunflower[half=upper]")))
        assertEquals(here.copy(y = 71), structuralPartnerOf(here, shape("large_fern[half=lower]")))
    }

    @Test
    fun `an extended piston points at its head, an unextended one at nothing`() {
        assertEquals(here.copy(y = 71), structuralPartnerOf(here, shape("piston[extended=true,facing=up]")))
        assertEquals(here.copy(x = 9), structuralPartnerOf(here, shape("sticky_piston[extended=true,facing=west]")))
        assertNull(structuralPartnerOf(here, shape("piston[extended=false,facing=up]")))
    }

    @Test
    fun `a piston head points back at its base`() {
        assertEquals(here.copy(y = 69), structuralPartnerOf(here, shape("piston_head[facing=up,type=normal]")))
    }

    @Test
    fun `an unpaired block points at nothing`() {
        assertNull(structuralPartnerOf(here, shape("stone")))
        assertNull(structuralPartnerOf(here, shape("oak_stairs[facing=north,half=bottom]")))
        assertNull(structuralPartnerOf(here, BlockShape.AIR))
    }
}
