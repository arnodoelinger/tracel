package com.tracel.plugin.startup.version

import com.tracel.plugin.util.fail
import io.github.z4kn4fein.semver.toVersionOrNull
import org.bukkit.plugin.Plugin

/** Minecraft version checker. */
data class MinecraftVersion(val major: Int, val minor: Int) : Comparable<MinecraftVersion> {
    override fun compareTo(other: MinecraftVersion): Int =
        compareBy<MinecraftVersion>({ it.major }, { it.minor }).compare(this, other)

    override fun toString(): String = "$major.$minor"

    companion object {
        val oldest get() = SUPPORTED_VERSIONS.min()
        val newest get() = SUPPORTED_VERSIONS.max()

        /** Parse Minecraft version. */
        fun parse(raw: String): MinecraftVersion? =
            raw.toVersionOrNull(strict = false)?.let { MinecraftVersion(it.major, it.minor) }

        /** Check if the version is supported. */
        fun check(raw: String, plugin: Plugin): Boolean {
            val version = parse(raw) ?: fail(
                plugin,
                "Tracel could not read Minecraft version \"$raw\". Oldest supported is $oldest."
            )
            if (version < oldest) fail(
                plugin,
                "Tracel does not run on Minecraft $version. Versions below $oldest are not supported."
            )
            return (version !in SUPPORTED_VERSIONS).also { if (it) plugin.logger.warning("Tracel has not been tested on Minecraft $version. Use with caution!") }
        }
    }
}
