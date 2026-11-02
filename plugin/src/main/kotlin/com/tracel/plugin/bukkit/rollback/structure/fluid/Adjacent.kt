package com.tracel.plugin.bukkit.rollback.structure.fluid

import com.tracel.annotations.Unstable
import org.bukkit.block.BlockFace

/** The six faces that share a side with a block. */
@Unstable
internal val ADJACENT: List<BlockFace> = BlockFace.entries.filter { it.isCartesian }
