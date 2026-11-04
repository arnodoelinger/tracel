package com.tracel.plugin.util.geometry

import com.tracel.model.world.TILE_SHIFT
import com.tracel.model.world.packPair
import com.tracel.model.world.pairHi
import com.tracel.model.world.pairLo

/** The chunk a block coordinate falls in, as a `Long`: 32 bits chunk x, 32 bits chunk z. */
internal fun chunkKey(x: Int, z: Int): Long = packPair(x shr TILE_SHIFT, z shr TILE_SHIFT)

/** X out of a key from [chunkKey]. */
internal fun chunkKeyX(key: Long): Int = pairHi(key)

/** Z out of a key from [chunkKey]. */
internal fun chunkKeyZ(key: Long): Int = pairLo(key)
