package com.tracel.plugin.adapter.rollback.material.item

import com.tracel.plugin.specifics.inventory.BrewingSlot
import com.tracel.plugin.specifics.inventory.FurnaceSlot
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice

internal fun furnaceSlot(stack: ItemStack): Int = when {
    stack.type.isFuel && !isSmeltable(stack.type) -> FurnaceSlot.FUEL.slot
    isCooked(stack.type) -> FurnaceSlot.RESULT.slot
    else -> FurnaceSlot.INPUT.slot
}

internal fun brewingSlots(stack: ItemStack): List<Int> = BrewingSlot.of(stack.type).slots

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
