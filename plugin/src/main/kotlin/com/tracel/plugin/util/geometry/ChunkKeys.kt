package com.tracel.plugin.util.geometry

/** Packs a chunk coordinate into a `Long`: 32 bits [x], 32 bits [z]. */
internal fun chunkKey(x: Int, z: Int): Long = ((x shr 4).toLong() shl 32) or ((z shr 4).toLong() and 0xffffffffL)

/** X out of a key from [chunkKey]. */
internal fun chunkKeyX(key: Long): Int = (key shr 32).toInt()

/** Z out of a key from [chunkKey]. */
internal fun chunkKeyZ(key: Long): Int = key.toInt()
