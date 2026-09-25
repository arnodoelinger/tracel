package com.tracel.plugin.adapter.item

import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.inventory.meta.CrossbowMeta
import org.bukkit.inventory.meta.Damageable
import kotlin.time.Duration.Companion.milliseconds
import com.tracel.plugin.TracelPlugin
import kotlin.collections.iterator

private val PLAIN_BYTES = ConcurrentHashMap<Material, ByteArray>()
private val NOT_AN_ITEM = ByteArray(0)

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

private val HEX = HexFormat.of()
private const val MAX_CACHED_KEYS = 16_384

private val KEYS = ConcurrentHashMap<ItemStack, ItemKey>()

/** Keys decided before a purge would skip remembering their forms again. */
internal fun forgetItemKeys() = KEYS.clear()

private fun plainBytesOf(material: Material): ByteArray? =
    PLAIN_BYTES.computeIfAbsent(material) {
        runCatching { ItemStack(it, 1).serializeAsBytes() }.getOrDefault(NOT_AN_ITEM)
    }.takeIf { it !== NOT_AN_ITEM }

/**
 * Decorated stacks seen on a region thread, waiting for the storage thread.
 *
 * Region thread cannot touch the store. The same enchanted sword shows up thousands
 * of times; [seen] writes it once. [TracelPlugin] drains this later.
 *
 * Dropping an entry loses fidelity. Missing bytes restore as a plain stack of the
 * right material — which is what every decorated item used to do anyway.
 */
object PendingItemForms {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentLinkedQueue<Pair<ContentHash, ByteArray>>()
    private val woken = Channel<Unit>(Channel.CONFLATED)
    private const val MAX_REMEMBERED = 1 shl 16

    /** Remember a decorated stack, waiting for the storage thread. */
    fun remember(hash: ContentHash, bytes: ByteArray) {
        // A full memory forgets, never refuses: a write twice costs nothing, a form never written is a plain restore
        if (seen.size >= MAX_REMEMBERED) seen.clear()
        if (!seen.add(hash.hex)) return
        pending += hash to bytes
        woken.trySend(Unit)
    }

    /** Sleep until there is a batch, or [timeoutMillis] runs out. */
    suspend fun awaitWork(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis.milliseconds) { woken.receive() }
    }

    /** Find a decorated stack or return a plain stack of the right material. */
    fun find(hash: ContentHash): ByteArray? {
        for ((queued, bytes) in pending) if (queued == hash) return bytes
        return null
    }

    /**
     * Put a failed write back.
     *
     * [seen] means "already handled". Drain, hand to storage, throw — queue empty,
     * hash still marked seen; those bytes never come back for the life of the
     * process.
     *
     * The next restore of that map is a blank page.
     */
    fun requeue(forms: Map<ContentHash, ByteArray>) {
        for ((hash, bytes) in forms) pending += hash to bytes
    }

    /** Drain the pending queue, returning a map of hashes to bytes. */
    fun drain(): Map<ContentHash, ByteArray> {
        if (pending.isEmpty()) return emptyMap()
        val out = HashMap<ContentHash, ByteArray>()
        while (true) {
            val (hash, bytes) = pending.poll() ?: break
            out[hash] = bytes
        }
        return out
    }

    /** After a purge, write everything again. */
    fun reset() {
        seen.clear()
        pending.clear()
        forgetItemKeys()
    }
}
