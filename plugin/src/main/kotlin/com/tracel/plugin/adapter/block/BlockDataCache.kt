package com.tracel.plugin.adapter.block

import com.tracel.model.world.block.BlockDataKey
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Bukkit
import org.bukkit.block.data.BlockData

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
    fun of(key: BlockDataKey): BlockData? {
        parsed[key.value]?.let { return it }
        if (key.value in unparseable) return null

        val data = runCatching { Bukkit.createBlockData(key.value) }.getOrNull()
        if (data == null) {
            unparseable += key.value
            return null
        }
        parsed[key.value] = data
        return data
    }
}
