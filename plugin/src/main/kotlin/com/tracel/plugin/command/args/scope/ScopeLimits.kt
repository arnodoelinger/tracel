package com.tracel.plugin.command.args.scope

object ScopeLimits {
    const val MAX_BLOCK_RADIUS: Int = 1024
    const val MAX_CHUNK_RADIUS: Int = MAX_BLOCK_RADIUS shr 4

    @Volatile
    var rollbackMaxBlocks: Int? = MAX_BLOCK_RADIUS

    /** Whether it reaches past [maxBlocks] (or [maxBlocks] shifted to chunks); `null` limits nothing. */
    fun LookupScope.isOversized(maxBlocks: Int? = MAX_BLOCK_RADIUS): Boolean = when (this) {
        is LookupScope.Blocks -> maxBlocks != null && radius > maxBlocks
        is LookupScope.Chunks -> maxBlocks != null && radius > (maxBlocks shr 4)
        LookupScope.CurrentChunk, LookupScope.CurrentBlock -> false
    }
}
