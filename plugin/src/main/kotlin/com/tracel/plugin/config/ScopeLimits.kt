package com.tracel.plugin.config

/** How far a scope may reach. */
object ScopeLimits {
    const val MAX_BLOCK_RADIUS: Int = 1024
    const val MAX_CHUNK_RADIUS: Int = MAX_BLOCK_RADIUS shr 4

    @Volatile
    var rollbackMaxBlocks: Int? = MAX_BLOCK_RADIUS
}
