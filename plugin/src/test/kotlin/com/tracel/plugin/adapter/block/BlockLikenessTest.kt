package com.tracel.plugin.adapter.block

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlockLikenessTest {
    @Test
    fun `snowy grass is still grass`() {
        assertTrue(
            BlockLikeness.sameEnough(
                "minecraft:grass_block[snowy=true]",
                "minecraft:grass_block[snowy=false]",
            ),
        )
    }

    @Test
    fun `dirt that grew grass is still ground, not a rebuild`() {
        assertTrue(BlockLikeness.sameEnough("minecraft:grass_block", "minecraft:dirt"))
        assertTrue(BlockLikeness.sameEnough("minecraft:dirt", "minecraft:grass_block[snowy=false]"))
    }

    @Test
    fun `wet farmland is still farmland`() {
        assertTrue(
            BlockLikeness.sameEnough(
                "minecraft:farmland[moisture=7]",
                "minecraft:farmland[moisture=0]",
            ),
        )
    }

    @Test
    fun `oxidised copper is still the same copper block`() {
        assertTrue(
            BlockLikeness.sameEnough(
                "minecraft:oxidized_cut_copper_stairs[facing=north,half=bottom,shape=straight]",
                "minecraft:cut_copper_stairs[facing=north,half=bottom,shape=straight]",
            ),
        )
    }

    @Test
    fun `a plain copper block that weathered is still that copper block`() {
        assertTrue(BlockLikeness.sameEnough("minecraft:exposed_copper", "minecraft:copper_block"))
        assertTrue(BlockLikeness.sameEnough("minecraft:waxed_oxidized_copper", "minecraft:waxed_copper_block"))
        assertFalse(BlockLikeness.sameEnough("minecraft:raw_copper_block", "minecraft:copper_block"))
        assertFalse(BlockLikeness.sameEnough("minecraft:copper_ore", "minecraft:copper_block"))
    }

    @Test
    fun `a chest is not dirt`() {
        assertFalse(BlockLikeness.sameEnough("minecraft:chest[facing=north]", "minecraft:dirt"))
    }

    @Test
    fun `stairs facing the other way are a real rebuild`() {
        assertFalse(
            BlockLikeness.sameEnough(
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]",
                "minecraft:oak_stairs[facing=south,half=bottom,shape=straight]",
            ),
        )
    }

    @Test
    fun `a repeated question gets the same answer, family or no family`() {
        repeat(3) {
            assertFalse(BlockLikeness.sameEnough("minecraft:chest[facing=north]", "minecraft:dirt"))
            assertTrue(BlockLikeness.sameEnough("minecraft:grass_block", "minecraft:dirt"))
            assertFalse(BlockLikeness.sameEnough("minecraft:stone", "minecraft:cobblestone"))
        }
    }

    @Test
    fun `two states of the same material keep their own stripped forms`() {
        assertTrue(BlockLikeness.sameEnough("minecraft:oak_leaves[distance=1]", "minecraft:oak_leaves[distance=7]"))
        assertFalse(
            BlockLikeness.sameEnough(
                "minecraft:oak_leaves[distance=1,waterlogged=true]",
                "minecraft:oak_leaves[distance=1,waterlogged=false]",
            ),
        )
    }
}
