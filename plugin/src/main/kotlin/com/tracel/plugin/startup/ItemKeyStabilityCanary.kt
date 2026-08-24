package com.tracel.plugin.startup

import com.tracel.plugin.convert.toItemKey
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

private const val GOLDEN_RESOURCE = "item-key-goldens.properties"

/**
 * Catches Mojang silently changing `ItemStack.serializeAsBytes()`'s wire format — which would
 * otherwise poison every decorated `item_key` without a single exception being thrown anywhere.
 */
object ItemKeyStabilityCanary {
    private fun representativeItems(): Map<String, ItemStack> = mapOf(
        "enchanted diamond sword" to ItemStack(Material.DIAMOND_SWORD).apply {
            addUnsafeEnchantment(Enchantment.SHARPNESS, 5)
        },
        "custom-named stick" to ItemStack(Material.STICK).apply {
            itemMeta = itemMeta?.also { it.setDisplayName("Wand of Testing") }
        },
        "custom-model-data paper" to ItemStack(Material.PAPER).apply {
            itemMeta = itemMeta?.also { it.setCustomModelData(1) }
        },
    )

    fun check(plugin: Plugin, logger: Logger) {
        val goldenText = plugin.getResource(GOLDEN_RESOURCE)?.bufferedReader()?.readText().orEmpty()
        val goldens = parseGoldens(goldenText)
        val actual = representativeItems().mapValues { (_, stack) -> stack.toItemKey().decoration!!.hex }

        for (result in checkGoldens(goldens, actual)) {
            when (result) {
                is GoldenResult.Match -> Unit
                is GoldenResult.Unrecorded -> logger.info(
                    "item_key golden not yet recorded for '${result.name}' — " +
                        "add `${result.name}=${result.actualHex}` to $GOLDEN_RESOURCE once this server's " +
                        "Minecraft version is one you trust as a baseline.",
                )
                is GoldenResult.Drifted -> logger.severe(
                    "item_key format drift detected for '${result.name}': expected ${result.expectedHex}, " +
                        "got ${result.actualHex}. Every already-recorded item_key for this shape of item no " +
                        "longer matches newly captured ones — see PLAN.md \"item_key и граница версий\" " +
                        "before doing anything else with provenance data on this server.",
                )
            }
        }
    }
}
