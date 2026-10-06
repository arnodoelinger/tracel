package com.tracel.plugin.util.geometry

/** Packs a block coordinate into a `Long`: 26 bits [x], 26 bits [z], 12 bits [y]. */
internal fun packed(x: Int, y: Int, z: Int): Long =
    ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

/** X out of a key from [packed]. */
internal fun unpackX(key: Long): Int = (key shr 38).toInt()

/** Z out of a key from [packed]. */
internal fun unpackZ(key: Long): Int = (key shl 26 shr 38).toInt()

/** Y out of a key from [packed]. */
internal fun unpackY(key: Long): Int = (key shl 52 shr 52).toInt()
