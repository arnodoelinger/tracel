package com.tracel.plugin.specifics.server

/**
 * Server methods the section paste calls, by name.
 *
 * None of this is `Paper` API. A Minecraft update that renames one is fixed here; what it takes and
 * returns is spelled out where it is looked up, in `PasteBridge`.
 */
internal enum class ServerMethod(val id: String) {
    GET_HANDLE("getHandle"),
    GET_CHUNK_SOURCE("getChunkSource"),
    GET_LIGHT_ENGINE("getLightEngine"),
    GET_CHUNK_IF_LOADED("getChunkIfLoaded"),
    GET_SECTIONS("getSections"),
    GET_MIN_Y("getMinY"),
    GET_BLOCK_STATE("getBlockState"),
    SET_BLOCK_STATE("setBlockState"),
    HAS_ONLY_AIR("hasOnlyAir"),
    IS_AIR("isAir"),
    HAS_BLOCK_ENTITY("hasBlockEntity"),
    LIQUID("liquid"),
    CAN_BE_REPLACED("canBeReplaced"),
    GET_BUKKIT_MATERIAL("getBukkitMaterial"),
    GET_STATE("getState"),
    CREATE_DATA("createData"),
    UPDATE("update"),
    GET_FIRST_AVAILABLE("getFirstAvailable"),
    IS_OPAQUE("isOpaque"),
    MARK_UNSAVED("markUnsaved"),
    PACK("pack"),
    GET_VISIBLE_CHUNK_IF_PRESENT("getVisibleChunkIfPresent"),
    BLOCK_CHANGED("blockChanged"),
    MOONRISE_HAS_CHUNK_BEEN_SENT("moonrise\$hasChunkBeenSent"),
    SET("set"),
    HAS_POI("hasPoi"),
    UPDATE_POI_ON_BLOCK_STATE_CHANGE("updatePOIOnBlockStateChange"),
    HAS_DIFFERENT_LIGHT_PROPERTIES("hasDifferentLightProperties"),
    OF("of"),
    UPDATE_SECTION_STATUS("updateSectionStatus"),
    GET_POS("getPos"),
    MAYBE_HAS("maybeHas"),
    STARLIGHT_SERVER_RELIGHT_CHUNKS("starlight\$serverRelightChunks"),
}
