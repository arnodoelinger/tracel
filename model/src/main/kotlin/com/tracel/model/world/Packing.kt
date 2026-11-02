package com.tracel.model.world

/** Two `Int`s in one `Long`: [hi] in the high 32 bits, [lo] in the low 32. */
public fun packPair(hi: Int, lo: Int): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)

/** The high half out of a key from [packPair]. */
public fun pairHi(key: Long): Int = (key shr 32).toInt()

/** The low half out of a key from [packPair]. */
public fun pairLo(key: Long): Int = key.toInt()

/** A cell's coordinates in one `Long`: 26 bits [x], 26 bits [z], 12 bits [y]. Equal cells, equal keys. */
public fun packCell(x: Int, y: Int, z: Int): Long =
    ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

/** X out of a key from [packCell]. */
public fun unpackCellX(key: Long): Int = (key shr 38).toInt()

/** Y out of a key from [packCell]. */
public fun unpackCellY(key: Long): Int = (key shl 52 shr 52).toInt()

/** Z out of a key from [packCell]. */
public fun unpackCellZ(key: Long): Int = (key shl 26 shr 38).toInt()
