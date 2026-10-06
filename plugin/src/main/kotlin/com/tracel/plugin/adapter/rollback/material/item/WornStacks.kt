package com.tracel.plugin.adapter.rollback.material.item

import com.tracel.model.item.ItemKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * Stacks one restore took out of the world, handed to its gives of the same key.
 *
 * The key forgot the wear, so only the stack that was really taken still knows it.
 *
 * A template is a free repair.
 */
internal class WornStacks {
    private val taken = ConcurrentHashMap<ItemKey, ConcurrentLinkedQueue<ItemStack>>()

    fun took(itemKey: ItemKey, stack: ItemStack) {
        taken.computeIfAbsent(itemKey) { ConcurrentLinkedQueue() } += stack
    }

    fun next(itemKey: ItemKey): ItemStack? = taken[itemKey]?.poll()

    companion object {
        fun wears(itemKey: ItemKey): Boolean =
            runCatching { Material.valueOf(itemKey.material).maxDurability > 0 }.getOrDefault(false)
    }
}
