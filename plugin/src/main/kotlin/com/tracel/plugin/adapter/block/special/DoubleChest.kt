package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Chest

/**
 * The other half of a large chest.
 *
 * @see Chest
 */
@Unstable
internal object DoubleChest {
    fun partner(block: Block): Block? {
        val data = block.blockData as? Chest ?: return null
        val face = turn(data.facing, clockwise = data.type == Chest.Type.LEFT) ?: return null
        return runCatching { block.getRelative(face).takeIf { it.type == block.type } }.getOrNull()
    }

    private fun turn(face: BlockFace, clockwise: Boolean): BlockFace? = when (face) {
        BlockFace.NORTH -> if (clockwise) BlockFace.EAST else BlockFace.WEST
        BlockFace.EAST -> if (clockwise) BlockFace.SOUTH else BlockFace.NORTH
        BlockFace.SOUTH -> if (clockwise) BlockFace.WEST else BlockFace.EAST
        BlockFace.WEST -> if (clockwise) BlockFace.NORTH else BlockFace.SOUTH
        else -> null
    }
}
