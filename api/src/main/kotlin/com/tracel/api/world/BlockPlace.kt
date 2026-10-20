package com.tracel.api.world

import com.tracel.api.Unstable
import java.util.UUID
import org.bukkit.Location
import org.bukkit.block.Block

/**
 * A block position, copied off the live world the moment it is made.
 *
 * It holds no `Block` and no `World`, so it is safe on any thread and outlives chunk unloads.
 *
 * To touch the real block again, ask `Tracel` to run on the region that owns it. Otherwise,
 * go fuck yourself.
 */
@Unstable
public data class BlockPlace(public val world: UUID, public val x: Int, public val y: Int, public val z: Int) {
    override fun toString(): String = "$x $y $z"

    public companion object {
        @JvmStatic
        public fun of(block: Block): BlockPlace = BlockPlace(block.world.uid, block.x, block.y, block.z)

        @JvmStatic
        public fun of(location: Location): BlockPlace? {
            val world = location.world ?: return null
            return BlockPlace(world.uid, location.blockX, location.blockY, location.blockZ)
        }
    }
}

/** Where this block is, as a [BlockPlace]. */
@Unstable
public val Block.place: BlockPlace get() = BlockPlace.of(this)
