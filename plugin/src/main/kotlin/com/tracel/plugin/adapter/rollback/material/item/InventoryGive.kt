package com.tracel.plugin.adapter.rollback.material.item

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.model.item.ItemKey
import com.tracel.plugin.specifics.entity.MOUNT_STORAGE_FROM
import org.bukkit.block.Crafter
import org.bukkit.inventory.*

/** Give as real leftover stacks. */
internal fun giveInto(
    inventory: Inventory,
    itemKey: ItemKey,
    amount: Long,
    template: ItemStack,
    preferredSlots: List<ContainerSlotEntry> = emptyList(),
): List<ItemStack> {
    if (amount <= 0L) return emptyList()
    val over = ArrayList<ItemStack>()

    if (inventory.hasSlotsWithRoles()) {
        var roleLeft = amount
        for ((slot, _, quantity) in preferredSlots) {
            if (roleLeft <= 0L) break
            val take = minOf(roleLeft, quantity)
            if (take <= 0L) continue
            val left = fill(inventory, slot, template.clone().apply { this.amount = take.toInt() })
            roleLeft -= take - (left?.amount?.toLong() ?: 0L)
        }
        for (stack in stacksOf(itemKey, roleLeft, template)) {
            if (stack.isEmpty || stack.type.isAir) continue
            val left = intoRoleSlot(inventory, stack) ?: continue
            over += if (inventory is AbstractHorseInventory) intoStorage(inventory, left) else listOf(left)
        }
        return over
    }

    if (inventory is ChiseledBookshelfInventory) {
        over += intoSingleItemSlots(inventory, itemKey, amount, template)
        return over
    }

    var remaining = amount
    for ((slot, _, quantity) in preferredSlots) {
        if (remaining <= 0L) break
        val take = minOf(remaining, quantity)
        if (take <= 0L) continue
        val left = fill(inventory, slot, template.clone().apply { this.amount = take.toInt() })
        remaining -= take - (left?.amount?.toLong() ?: 0L)
    }
    if (remaining > 0L) {
        for (stack in stacksOf(itemKey, remaining, template)) {
            if (stack.isEmpty || stack.type.isAir) continue
            over += inventory.addItem(stack).values
        }
    }
    return over
}

/** Split to `maxStackSize`. */
internal fun stacksOf(itemKey: ItemKey, amount: Long, template: ItemStack?): List<ItemStack> {
    if (amount <= 0L) return emptyList()
    val base = template ?: return emptyList()
    val max = base.maxStackSize.coerceAtLeast(1).toLong()
    val out = ArrayList<ItemStack>()
    var remaining = amount
    while (remaining > 0L) {
        val take = minOf(remaining, max)
        out += base.clone().apply { this.amount = take.toInt() }
        remaining -= take
    }
    return out
}

private fun Inventory.hasSlotsWithRoles(): Boolean =
    this is FurnaceInventory || this is BrewerInventory || this is SaddledMountInventory

private fun intoSingleItemSlots(
    inventory: Inventory,
    itemKey: ItemKey,
    amount: Long,
    template: ItemStack,
): List<ItemStack> {
    var remaining = amount
    for (slot in 0 until inventory.size) {
        if (remaining <= 0L) break
        val held = inventory.getItem(slot)
        if (held != null && !held.isEmpty && !held.type.isAir) continue
        inventory.setItem(slot, template.clone().apply { this.amount = 1 })
        remaining -= 1L
    }
    if (remaining <= 0L) return emptyList()
    return stacksOf(itemKey, remaining, template)
}

private fun intoRoleSlot(inventory: Inventory, stack: ItemStack): ItemStack? = when (inventory) {
    is FurnaceInventory -> fill(inventory, furnaceSlot(stack), stack)
    is BrewerInventory -> {
        var left: ItemStack? = stack
        for (slot in brewingSlots(stack)) left = left?.let { fill(inventory, slot, it) }
        left
    }

    is SaddledMountInventory -> intoTack(inventory, stack)
    else -> stack
}

private fun intoStorage(inventory: Inventory, stack: ItemStack): List<ItemStack> {
    val crafter = runCatching { inventory.holder as? Crafter }.getOrNull()
    if (crafter != null) return intoCrafter(inventory, crafter, stack)
    if (inventory !is AbstractHorseInventory) return inventory.addItem(stack).values.toList()
    var left: ItemStack? = stack
    for (slot in MOUNT_STORAGE_FROM until inventory.size) {
        val current = left ?: break
        val held = inventory.getItem(slot)
        if (held == null || held.isEmpty || held.type.isAir) continue
        left = fill(inventory, slot, current)
    }
    for (slot in MOUNT_STORAGE_FROM until inventory.size) {
        val current = left ?: break
        left = fill(inventory, slot, current)
    }
    return listOfNotNull(left)
}

private fun intoCrafter(inventory: Inventory, crafter: Crafter, stack: ItemStack): List<ItemStack> {
    var left: ItemStack? = stack
    for (pass in 0..1) {
        for (slot in 0 until inventory.size) {
            val current = left ?: break
            if (runCatching { crafter.isSlotDisabled(slot) }.getOrDefault(false)) continue
            val held = inventory.getItem(slot)
            if (pass == 0 && (held == null || held.isEmpty || held.type.isAir)) continue
            left = fill(inventory, slot, current)
        }
    }
    return listOfNotNull(left)
}

private fun fill(inventory: Inventory, slot: Int, stack: ItemStack): ItemStack? {
    if (slot >= inventory.size) return stack
    val held = inventory.getItem(slot)
    if (held == null || held.isEmpty || held.type.isAir) {
        val take = minOf(stack.amount, stack.maxStackSize.coerceAtLeast(1))
        inventory.setItem(slot, stack.clone().apply { amount = take })
        return stack.takeIf { it.amount > take }?.clone()?.apply { amount = stack.amount - take }
    }
    if (!held.isSimilar(stack)) return stack
    val room = held.maxStackSize - held.amount
    if (room <= 0) return stack
    val take = minOf(room, stack.amount)
    inventory.setItem(slot, held.clone().apply { amount = held.amount + take })
    return stack.takeIf { it.amount > take }?.clone()?.apply { amount = stack.amount - take }
}
