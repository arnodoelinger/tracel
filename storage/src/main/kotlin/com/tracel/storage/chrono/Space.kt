package com.tracel.storage.chrono

/**
 * One cell inside a tile: `x` and `z` are the low four bits of the world coordinates,
 * `y` is the whole height, signed.
 */
@JvmInline
value class Cell(val packed: Int) {
    val x: Int get() = packed and 15
    val z: Int get() = (packed ushr 4) and 15
    val y: Int get() = packed shr 8

    override fun toString(): String = "Cell($x, $y, $z)"

    companion object {
        fun of(x: Int, y: Int, z: Int): Cell {
            require(y in MIN_Y..MAX_Y) { "y $y is outside what a cell holds." }
            return Cell((y shl 8) or ((z and 15) shl 4) or (x and 15))
        }

        const val MIN_Y: Int = -(1 shl 23)
        const val MAX_Y: Int = (1 shl 23) - 1
    }
}

/** One column of 16 x 16 cells in one world, all three numbers in a long: world on top, then `x`, then `z`. */
@JvmInline
value class Tile(val packed: Long) {
    val world: Int get() = (packed ushr (AXIS_BITS * 2)).toInt()

    val x: Int get() = ((packed shl WORLD_BITS) shr (64 - AXIS_BITS)).toInt()
    val z: Int get() = ((packed shl (64 - AXIS_BITS)) shr (64 - AXIS_BITS)).toInt()

    val originX: Int get() = x shl SHIFT
    val originZ: Int get() = z shl SHIFT

    override fun toString(): String = "Tile($world: $x, $z)"

    companion object {
        const val SHIFT: Int = 4
        const val WORLD_BITS: Int = 20
        const val AXIS_BITS: Int = 22
        private const val AXIS_MAX: Int = (1 shl (AXIS_BITS - 1)) - 1
        private const val AXIS_MIN: Int = -(1 shl (AXIS_BITS - 1))

        fun of(world: Int, x: Int, z: Int): Tile {
            require(world in 0 until (1 shl WORLD_BITS)) { "world $world does not fit $WORLD_BITS bits." }
            require(x in AXIS_MIN..AXIS_MAX && z in AXIS_MIN..AXIS_MAX) { "tile $x, $z is past the edge of any world." }
            val axisMask = (1L shl AXIS_BITS) - 1
            return Tile((world.toLong() shl (AXIS_BITS * 2)) or ((x.toLong() and axisMask) shl AXIS_BITS) or (z.toLong() and axisMask))
        }

        fun at(world: Int, x: Int, z: Int): Tile = of(world, x shr SHIFT, z shr SHIFT)
    }
}
