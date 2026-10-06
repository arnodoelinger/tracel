package com.tracel.plugin.adapter.rollback.structure.block.write

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.rollback.structure.block.paint
import com.tracel.plugin.specifics.block.BubbleMaker
import org.bukkit.Material
import org.bukkit.block.*
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.type.BubbleColumn

private const val MAX_BUBBLE_COLUMN = 384

@Unstable
internal fun Block.wakeBubbles() {
    val drag = BubbleMaker.dragOf(type)
    var cell = getRelative(BlockFace.UP)
    var left = MAX_BUBBLE_COLUMN
    while (left-- > 0) {
        val data = cell.blockData
        val source = (data as? Levelled)?.let { cell.type == Material.WATER && it.level == 0 } ?: false
        when {
            drag != null && (source || cell.type == Material.BUBBLE_COLUMN) ->
                cell.paint(
                    (Material.BUBBLE_COLUMN.createBlockData() as BubbleColumn).also { it.isDrag = drag },
                )

            drag == null && cell.type == Material.BUBBLE_COLUMN -> cell.paint(Material.WATER.createBlockData())

            else -> return
        }
        cell = cell.getRelative(BlockFace.UP)
    }
}
