package com.tracel.plugin.convert

import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import org.bukkit.inventory.ItemStack
import java.security.MessageDigest

/**
 * [ItemKey.decoration] is only set for meta that actually affects interchangeability — matches
 * [ItemKey]'s own contract. The hash itself covers every serialized component of the stack
 * (not just enchantments / name / model data individually), so a lore change or a hidden
 * attribute modifier still produces a different key instead of being silently ignored.
 */
fun ItemStack.toItemKey(): ItemKey {
    val meta = itemMeta
    val decorated = meta != null && (meta.hasEnchants() || meta.hasDisplayName() || meta.hasCustomModelDataComponent())
    return ItemKey(type.name, if (decorated) ContentHash(hashDecoration(this)) else null)
}

private fun hashDecoration(stack: ItemStack): String {
    val normalized = stack.clone().apply { amount = 1 }
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.serializeAsBytes())
    return digest.joinToString(separator = "") { "%02x".format(it) }
}
