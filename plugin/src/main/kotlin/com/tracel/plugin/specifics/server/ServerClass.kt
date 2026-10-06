package com.tracel.plugin.specifics.server

/**
 * Server classes the section paste reaches into, by name.
 *
 * None of this is `Paper` API. A Minecraft update that moves or renames one is fixed here.
 */
internal enum class ServerClass(val className: String) {
    CRAFT_WORLD("org.bukkit.craftbukkit.CraftWorld"),
    SERVER_LEVEL("net.minecraft.server.level.ServerLevel"),
    LEVEL("net.minecraft.world.level.Level"),
    CHUNK_SOURCE("net.minecraft.server.level.ServerChunkCache"),
    CHUNK_MAP("net.minecraft.server.level.ChunkMap"),
    LIGHT("net.minecraft.server.level.ThreadedLevelLightEngine"),
    CHUNK_ACCESS("net.minecraft.world.level.chunk.ChunkAccess"),
    LEVEL_CHUNK("net.minecraft.world.level.chunk.LevelChunk"),
    SECTION("net.minecraft.world.level.chunk.LevelChunkSection"),
    BLOCK_STATE("net.minecraft.world.level.block.state.BlockState"),
    STATE_BASE("net.minecraft.world.level.block.state.BlockBehaviour\$BlockStateBase"),
    CRAFT_DATA("org.bukkit.craftbukkit.block.data.CraftBlockData"),
    HEIGHTMAP("net.minecraft.world.level.levelgen.Heightmap"),
    HEIGHTMAP_TYPES("net.minecraft.world.level.levelgen.Heightmap\$Types"),
    CHUNK_POS("net.minecraft.world.level.ChunkPos"),
    CHUNK_HOLDER("net.minecraft.server.level.ChunkHolder"),
    BLOCK_POS("net.minecraft.core.BlockPos"),
    MUTABLE_BLOCK_POS("net.minecraft.core.BlockPos\$MutableBlockPos"),
    SECTION_POS("net.minecraft.core.SectionPos"),
    POI_TYPES("net.minecraft.world.entity.ai.village.poi.PoiTypes"),
    LIGHT_ENGINE("net.minecraft.world.level.lighting.LightEngine"),

    ;

    /** The class itself. Throws on a server that does not have it, which turns the section paste off. */
    fun load(): Class<*> = Class.forName(className)
}
