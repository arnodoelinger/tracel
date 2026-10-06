package com.tracel.plugin.integration.worldedit

import com.tracel.engine.world.edit.BlockEdit
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.util.isAirLike

/**
 * What one `WorldEdit` session has written so far, waiting to be logged.
 *
 * A cell written twice is one change: the first shape it had, the last one it was given. A cell that
 * ended where it started is no change at all. Not thread-safe; the owner locks.
 */
internal class EditBuffer(private val world: WorldId) {
    private val edits = LinkedHashMap<Long, BlockEdit>()

    /** How many cells are waiting. */
    val size: Int get() = edits.size

    /** @return `true` if this is the first cell since the buffer was last emptied. */
    fun note(x: Int, y: Int, z: Int, before: BlockShape, after: BlockShape): Boolean {
        val wasEmpty = edits.isEmpty()
        val key = packed(x, y, z)
        val first = edits[key]?.before ?: before
        edits[key] = BlockEdit(BlockPos(world, x, y, z), first, after)
        return wasEmpty
    }

    /** Takes everything out, grouped by what it did to its cell. Cells that came back to where they were are dropped. */
    fun drain(): Map<ActionKind, List<BlockEdit>> {
        val grouped = LinkedHashMap<ActionKind, MutableList<BlockEdit>>()
        for (edit in edits.values) {
            if (edit.before == edit.after) continue
            if (edit.before.isAirLike && edit.after.isAirLike) continue
            grouped.getOrPut(actionOf(edit)) { ArrayList() } += edit
        }
        edits.clear()
        return grouped
    }

    internal companion object {
        /** Block position. */
        fun packed(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF) // x, z = 26; y = 12

        /** @return what kind of change this edit represents. */
        fun actionOf(edit: BlockEdit): ActionKind = when {
            edit.before.isAirLike -> ActionKind.BLOCK_PLACE
            edit.after.isAirLike -> ActionKind.BLOCK_BREAK
            else -> ActionKind.BLOCK_CHANGE
        }
    }
}
