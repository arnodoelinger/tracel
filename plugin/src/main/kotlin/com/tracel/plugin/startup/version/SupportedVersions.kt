package com.tracel.plugin.startup.version

import com.tracel.platform.Versions

/**
 * Minecraft releases `Tracel` has been tested on, from [Versions.Minecraft.SUPPORTED].
 *
 * Patch versions are covered automatically.
 */
val SUPPORTED_VERSIONS: List<MinecraftVersion> =
    Versions.Minecraft.SUPPORTED.map { (major, minor) -> MinecraftVersion(major, minor) }
