package com.tracel.plugin.adapter.block

import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Material
import org.bukkit.block.Campfire
import org.bukkit.inventory.InventoryHolder

private val holdsItems = ConcurrentHashMap<String, Boolean>()

/** Whether the block named by [blockData] (`minecraft:chest[facing=north]`) keeps items in it. Asked of the block itself. */
internal fun containerBlockNamed(blockData: String): Boolean {
    val name = blockData.substringBefore('[').substringAfter(':').uppercase()
    return holdsItems.computeIfAbsent(name) {
        val material = Material.getMaterial(it)?.takeIf { m -> m.isBlock } ?: return@computeIfAbsent false
        runCatching {
            val state = material.createBlockData().createBlockState()
            state is InventoryHolder || state is Campfire
        }.getOrDefault(false)
    }
}
