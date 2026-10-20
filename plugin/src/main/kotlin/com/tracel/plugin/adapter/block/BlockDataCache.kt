package com.tracel.plugin.adapter.block

import com.tracel.model.world.block.BlockDataKey
import org.bukkit.Bukkit
import org.bukkit.block.data.BlockData
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches parsed block states.
 *
 * Cached [BlockData] instances must be treated as read-only. Clone one before
 * changing its properties.
 */
object BlockDataCache {
    private val parsed = ConcurrentHashMap<String, BlockData>()
    private val unparseable = ConcurrentHashMap.newKeySet<String>()

    /** @return the parsed block state for [key], or `null` if it cannot be parsed. */
    fun of(key: BlockDataKey): BlockData? = of(key.value)

    /**
     * @return the parsed block state for [value], as `Bukkit.createBlockData` reads it,
     * or `null` if it cannot be.
     */
    fun of(value: String): BlockData? {
        parsed[value]?.let { return it }
        if (value in unparseable) return null

        val data = runCatching { Bukkit.createBlockData(value) }.getOrNull()
        if (data == null) {
            unparseable += value
            return null
        }
        parsed[value] = data
        return data
    }
}
