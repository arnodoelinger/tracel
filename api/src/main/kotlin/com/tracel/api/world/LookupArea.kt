package com.tracel.api.world

import com.tracel.api.Unstable
import java.util.UUID

/**
 * Where a query looks.
 *
 * Plain coordinates, like [BlockPlace], so it is safe on any thread.
 */
@Unstable
public sealed interface LookupArea {
    /** The world it lies in. */
    public val world: UUID

    /** Every block within [radius] of [center] along each axis. */
    public data class Around(public val center: BlockPlace, public val radius: Int) : LookupArea {
        init {
            require(radius >= 0) { "Radius must not be negative, was $radius." }
        }

        override val world: UUID get() = center.world
    }

    /** Every block between two corners, both included, in whatever order they come. */
    public data class Cube(public val from: BlockPlace, public val to: BlockPlace) : LookupArea {
        init {
            require(from.world == to.world) { "Both corners of a cube must be in one world." }
        }

        override val world: UUID get() = from.world
        public val minX: Int get() = minOf(from.x, to.x)
        public val minY: Int get() = minOf(from.y, to.y)
        public val minZ: Int get() = minOf(from.z, to.z)
        public val maxX: Int get() = maxOf(from.x, to.x)
        public val maxY: Int get() = maxOf(from.y, to.y)
        public val maxZ: Int get() = maxOf(from.z, to.z)
    }

    /** All of one world. */
    public data class World(override val world: UUID) : LookupArea

    public companion object {
        @JvmStatic
        public fun of(place: BlockPlace): LookupArea = Cube(place, place)
    }
}
