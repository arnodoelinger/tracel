package com.tracel.plugin.rollback.material.item

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.canCarry
import com.tracel.plugin.adapter.item.carried
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.withCarried
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Crafter
import org.bukkit.inventory.AbstractHorseInventory
import org.bukkit.inventory.ArmoredHorseInventory
import org.bukkit.inventory.BrewerInventory
import org.bukkit.inventory.ChiseledBookshelfInventory
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.FurnaceInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.LlamaInventory
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.SaddledMountInventory

private const val HORSE_STORAGE_FROM = 2

/**
 * Takes [amount] of [itemKey] out of [inventory], matched by key.
 *
 * Empty bundles go before full ones, and a full one taken leaves what it held behind in [loose]: the
 * ledger books that apart from the bundle. Short of loose stacks, it takes from inside bundles.
 */
internal fun takeByKey(
    inventory: Inventory,
    itemKey: ItemKey,
    amount: Long,
    worn: WornStacks? = null,
    loose: (ItemStack) -> Unit = {},
): Long {
    if (amount <= 0L) return 0L
    var remaining = amount
    val contents = inventory.contents
    val order = contents.indices.sortedBy { slot -> contents[slot]?.let { it.canCarry() && it.carried().isNotEmpty() } == true }
    for (slot in order) {
        if (remaining <= 0L) break
        val stack = contents[slot] ?: continue
        if (stack.isEmpty || stack.type.isAir) continue
        if (!stack.matches(itemKey)) continue
        val take = minOf(remaining, stack.amount.toLong()).toInt()
        worn?.took(itemKey, stack.clone().apply { this.amount = take })
        remaining -= take
        val inside = stack.carried()
        if (take >= stack.amount) {
            inventory.setItem(slot, null)
        } else {
            stack.amount -= take
            inventory.setItem(slot, stack)
        }
        for (item in inside) item.clone().apply { this.amount *= take }.let(loose)
    }
    if (remaining > 0L) remaining = takeCarried(inventory, itemKey, remaining)
    return remaining
}

private fun takeCarried(inventory: Inventory, itemKey: ItemKey, amount: Long): Long {
    var remaining = amount
    val contents = inventory.contents
    for (slot in contents.indices) {
        if (remaining <= 0L) break
        val carrier = contents[slot] ?: continue
        if (!carrier.canCarry() || carrier.amount != 1) continue
        val inside = carrier.carried()
        if (inside.isEmpty()) continue
        var changed = false
        val kept = ArrayList<ItemStack>(inside.size)
        for (item in inside) {
            if (remaining > 0L && item.matches(itemKey)) {
                val take = minOf(remaining, item.amount.toLong()).toInt()
                remaining -= take
                changed = true
                if (take < item.amount) kept += item.clone().apply { this.amount -= take }
            } else {
                kept += item
            }
        }
        if (changed) inventory.setItem(slot, carrier.clone().withCarried(kept))
    }
    return remaining
}

/** Whether this stack is the material the ledger means by [itemKey]. */
internal fun ItemStack.matches(itemKey: ItemKey): Boolean {
    if (type.name != itemKey.material) return false
    return toItemKey() == itemKey
}

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

private fun intoTack(inventory: SaddledMountInventory, stack: ItemStack): ItemStack? {
    val worn: ItemStack?
    val wear: (ItemStack) -> Unit
    val name = stack.type.name
    when {
        name == "SADDLE" -> {
            worn = runCatching { inventory.saddle }.getOrNull()
            wear = { runCatching { inventory.saddle = it } }
        }
        inventory is ArmoredHorseInventory &&
            (name.endsWith("_HORSE_ARMOR") || name == "HORSE_ARMOR" || runCatching { stack.type.equipmentSlot }.getOrNull() == EquipmentSlot.BODY) -> {
            worn = runCatching { inventory.armor }.getOrNull()
            wear = { runCatching { inventory.armor = it } }
        }
        inventory is LlamaInventory && name.endsWith("_CARPET") -> {
            worn = runCatching { inventory.decor }.getOrNull()
            wear = { runCatching { inventory.decor = it } }
        }
        // Freight
        else -> return stack
    }
    // Already wearing one, spill the extra and don't conjure a second saddle
    if (worn != null && !worn.isEmpty && !worn.type.isAir) return stack
    wear(stack.clone().apply { amount = 1 })
    return stack.takeIf { it.amount > 1 }?.clone()?.apply { amount = stack.amount - 1 }
}

private fun intoStorage(inventory: Inventory, stack: ItemStack): List<ItemStack> {
    val crafter = runCatching { inventory.holder as? Crafter }.getOrNull()
    if (crafter != null) return intoCrafter(inventory, crafter, stack)
    if (inventory !is AbstractHorseInventory) return inventory.addItem(stack).values.toList()
    var left: ItemStack? = stack
    for (slot in HORSE_STORAGE_FROM until inventory.size) {
        val current = left ?: break
        val held = inventory.getItem(slot)
        if (held == null || held.isEmpty || held.type.isAir) continue
        left = fill(inventory, slot, current)
    }
    for (slot in HORSE_STORAGE_FROM until inventory.size) {
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

private fun furnaceSlot(stack: ItemStack): Int = when {
    stack.type.isFuel && !isSmeltable(stack.type) -> 1
    isCooked(stack.type) -> 2
    else -> 0
}

private fun brewingSlots(stack: ItemStack): List<Int> = when {
    stack.type == Material.BLAZE_POWDER -> listOf(4)
    isBottle(stack.type) -> listOf(0, 1, 2)
    else -> listOf(3)
}

private fun isBottle(material: Material): Boolean =
    material == Material.POTION || material == Material.SPLASH_POTION ||
        material == Material.LINGERING_POTION || material == Material.GLASS_BOTTLE

private val cooking: Pair<Set<Material>, Set<Material>> by lazy { cookingMaterials() }

private fun isCooked(material: Material): Boolean = material in cooking.first

private fun isSmeltable(material: Material): Boolean = material in cooking.second

@Suppress("DEPRECATION")
private fun cookingMaterials(): Pair<Set<Material>, Set<Material>> {
    val results = HashSet<Material>()
    val inputs = HashSet<Material>()
    runCatching {
        val recipes = Bukkit.recipeIterator()
        while (recipes.hasNext()) {
            val recipe = recipes.next() as? CookingRecipe<*> ?: continue
            results += recipe.result.type
            inputs += recipe.inputChoice.itemStack.type
            (recipe.inputChoice as? RecipeChoice.MaterialChoice)?.let { inputs += it.choices }
        }
    }
    return results to inputs
}
