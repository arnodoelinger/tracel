package com.tracel.api.addon

import com.tracel.api.Unstable
import com.tracel.api.item.ItemHolder
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.NamespacedName
import org.bukkit.inventory.ItemStack

/**
 * A kind of container the addon keeps: a backpack, a machine, an auction vault. Give it slots to read and write, and
 * `Tracel` works out the rest: what moved, who moved it, and how to put it back.
 *
 * Example:
 * ```
 * object Backpacks : ItemHolderType<UUID> {
 *     override val name = NamespacedName("backpacks", "backpack")
 *     override fun encode(id: UUID) = id.toString()
 *     override fun decode(text: String) = runCatching { UUID.fromString(text) }.getOrNull()
 *     override fun read(id: UUID) = storage[id]?.toList()
 *     override fun write(id: UUID, slot: Int, item: ItemStack?) { storage[id]?.set(slot, item) }
 * }
 * ```
 *
 * [I] is the addon's own ID type. History keeps it as [encode] spells it, so that spelling must never change.
 */
@Unstable
public interface ItemHolderType<I : Any> {
    /** What it is called; its namespace is the addon's ID. */
    public val name: NamespacedName

    /** [id] as text, for history. */
    public fun encode(id: I): String

    /** What [encode] wrote, or `null` for text that names nothing any more. */
    public fun decode(text: String): I?

    /** Its slots as they are now, empty ones `null`; `null` for a holder that is gone. */
    public fun read(id: I): List<ItemStack?>?

    /** Puts [item] into [slot], or empties it for `null`. */
    public fun write(id: I, slot: Int, item: ItemStack?)

    /**
     * Where it lives: `Tracel` calls [read] and [write] on the region thread that owns this block, or on the global
     * one for `null`.
     */
    public fun place(id: I): BlockPlace? = null
}

/** An [ItemHolderType] once registered. */
@Unstable
public interface RegisteredHolder<I : Any> {
    /** What was registered. */
    public val type: ItemHolderType<I>

    /** Holder [id], as history names it: for `recorder.watching` and for reading item flows. */
    public operator fun get(id: I): ItemHolder.Custom

    /** The ID [holder] stands for, or `null` when it is not one of these. */
    public fun idOf(holder: ItemHolder.Custom): I?
}

/** Where holder types are registered. */
@Unstable
public interface HolderTypes {
    /**
     * Registers [type] until the addon is disabled.
     *
     * A name outside the addon's namespace, or one taken, throws.
     */
    public fun <I : Any> register(type: ItemHolderType<I>): RegisteredHolder<I>
}
