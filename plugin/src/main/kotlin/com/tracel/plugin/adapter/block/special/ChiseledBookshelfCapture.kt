package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import org.bukkit.block.BlockState
import org.bukkit.block.ChiseledBookshelf

/**
 * Books in a chiseled bookshelf.
 *
 * @see ChiseledBookshelf
 */
@Unstable
internal object ChiseledBookshelfCapture {
    fun skipTileExtras(state: BlockState): Boolean = state is ChiseledBookshelf
}
