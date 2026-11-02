package com.tracel.plugin.bukkit.server

/** The heightmaps a chunk keeps, named as the server's own `Heightmap.Types` constants. */
internal enum class HeightmapType {
    MOTION_BLOCKING,
    MOTION_BLOCKING_NO_LEAVES,
    OCEAN_FLOOR,
    WORLD_SURFACE,
}
