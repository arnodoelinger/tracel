package com.tracel.plugin.command.args.scope

import com.tracel.plugin.config.ScopeLimits

/** Whether it reaches past [maxBlocks] (or [maxBlocks] shifted to chunks); `null` limits nothing. */
internal fun LookupScope.isOversized(maxBlocks: Int? = ScopeLimits.MAX_BLOCK_RADIUS): Boolean = when (this) {
    is LookupScope.Blocks -> maxBlocks != null && radius > maxBlocks
    is LookupScope.Chunks -> maxBlocks != null && radius > (maxBlocks shr 4)
    LookupScope.CurrentChunk, LookupScope.CurrentBlock -> false
}
