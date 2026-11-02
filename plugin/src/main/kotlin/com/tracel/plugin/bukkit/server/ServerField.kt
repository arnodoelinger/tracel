package com.tracel.plugin.bukkit.server

/** Server fields the section paste reads, by name. None of this is `Paper` API. */
internal enum class ServerField(val id: String) {
    CHUNK_MAP("chunkMap"),
    HEIGHTMAPS("heightmaps"),
    CAPTURE_BLOCK_STATES("captureBlockStates"),
    CAPTURE_TREE_GENERATION("captureTreeGeneration"),
}
