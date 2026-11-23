package com.tracel.engine.world.edit

/** A 16 x 16 x 16 cube of a world at one moment: which of its cells held nothing. */
public class EmptyCells(public val x: Int, public val y: Int, public val z: Int, public val bits: LongArray) {
    init {
        require(bits.size == CELLS / 64) { "a cube holds $CELLS cells, not ${bits.size * 64}." }
    }

    public companion object {
        public const val CELLS: Int = 4096
    }
}
