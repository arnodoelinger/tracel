package com.tracel.plugin.adapter.item

import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.inventory.meta.CrossbowMeta
import org.bukkit.inventory.meta.Damageable

private const val MAX_CACHED_KEYS = 16_384

private val PLAIN_BYTES = ConcurrentHashMap<Material, ByteArray>()
private val NOT_AN_ITEM = ByteArray(0)
private val KEYS = ConcurrentHashMap<ItemStack, ItemKey>()
private val HEX = HexFormat.of()

/** Key this stack under in the ledger. */
fun ItemStack.toItemKey(): ItemKey {
    if (!hasItemMeta()) return ItemKey(type.name)

    val normalized = clone().apply { amount = 1 }

    val meta = normalized.itemMeta
    var stripped = false
    if (meta is Damageable && meta.hasDamage()) {
        meta.damage = 0
        stripped = true
    }
    if (meta is BundleMeta && meta.hasItems()) {
        meta.setItems(null)
        stripped = true
    }
    if (meta is CrossbowMeta && meta.hasChargedProjectiles()) {
        meta.setChargedProjectiles(null)
        stripped = true
    }
    if (stripped) normalized.itemMeta = meta
    KEYS[normalized]?.let { return it }

    val bytes = normalized.serializeAsBytes()
    val key = if (bytes.contentEquals(plainBytesOf(type))) {
        ItemKey(type.name)
    } else {
        val hash = ContentHash(HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        PendingItemForms.remember(hash, bytes)
        ItemKey(type.name, hash)
    }
    if (KEYS.size >= MAX_CACHED_KEYS) KEYS.clear()
    KEYS[normalized] = key
    return key
}

/** Keys decided before a purge would skip remembering their forms again. */
internal fun forgetItemKeys() = KEYS.clear()

private fun plainBytesOf(material: Material): ByteArray? =
    PLAIN_BYTES.computeIfAbsent(material) {
        runCatching { ItemStack(it, 1).serializeAsBytes() }.getOrDefault(NOT_AN_ITEM)
    }.takeIf { it !== NOT_AN_ITEM }
