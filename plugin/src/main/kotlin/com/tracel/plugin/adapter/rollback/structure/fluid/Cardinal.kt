package com.tracel.plugin.adapter.rollback.structure.fluid

import com.tracel.annotations.Unstable
import org.bukkit.block.BlockFace

/** Cardinal directions are those that are orthogonal to the vertical axis. */
@Unstable
internal val CARDINAL: List<BlockFace> = BlockFace.entries.filter { it.isCartesian }
