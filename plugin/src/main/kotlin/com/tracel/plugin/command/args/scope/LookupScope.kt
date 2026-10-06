package com.tracel.plugin.command.args.scope

sealed interface LookupScope {
    data class Blocks(val radius: Int) : LookupScope
    data class Chunks(val radius: Int) : LookupScope
    data object CurrentChunk : LookupScope
    data object CurrentBlock : LookupScope
}
