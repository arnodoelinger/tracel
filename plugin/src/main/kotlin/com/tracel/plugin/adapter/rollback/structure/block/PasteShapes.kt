package com.tracel.plugin.adapter.rollback.structure.block

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.specifics.block.isMovingPiston
import com.tracel.plugin.specifics.block.isTreePart
import com.tracel.plugin.specifics.block.wakesBubbles
import org.bukkit.block.data.BlockData
import java.util.concurrent.ConcurrentHashMap

/**
 * A block shape and its traits, as seen by a paste. The traits are used to determine the order of placement and
 * removal, and to decide whether a block can be placed at all.
 */
@Unstable
internal class PasteShape(
    val data: BlockData?,
    val air: Boolean,
    val movingPiston: Boolean,
    val leafy: Boolean,
    val bubbly: Boolean,
) {
    @Volatile
    private var state: Any? = null

    /**
     * The server state of [data], or `null` when the paste cannot see one. Server states are the same objects for the
     * whole run, so the first one found is kept; a miss is not, since a paste that failed says nothing about the next.
     */
    fun stateIn(paste: PalettePaste): Any? {
        state?.let { return it }
        val found = paste.stateOf(data ?: return null) ?: return null
        state = found
        return found
    }
}

/** [PasteShape]s by state string. */
@Unstable
internal object PasteShapes {
    private val cache = ConcurrentHashMap<String, PasteShape>()

    /** The resolved [shape]. Its extras play no part; a shape with extras is not written by the section writer. */
    fun of(shape: BlockShape): PasteShape = cache.getOrPut(shape.data.value) { resolve(shape) }

    private fun resolve(shape: BlockShape): PasteShape {
        val data = BlockDataCache.of(shape.data)
        val material = data?.material
        return PasteShape(
            data = data,
            air = material?.isAir == true,
            movingPiston = shape.isMovingPiston(),
            leafy = data != null && isTreePart(data),
            bubbly = material != null && wakesBubbles(material),
        )
    }
}
