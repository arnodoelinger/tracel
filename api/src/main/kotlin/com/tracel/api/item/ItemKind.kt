package com.tracel.api.item

import com.tracel.api.Unstable

/**
 * What makes two stacks the same item.
 *
 * [material] is the `Bukkit` `Material` name.
 *
 * [variant] tells a renamed or enchanted one apart from a plain one, and is `null` for a plain one;
 * wear is not part of it.
 *
 * Get one off a stack with `stack.kind()` inside `HistoryReads`, or name a plain one with [of].
 */
@Unstable
public data class ItemKind(public val material: String, public val variant: String? = null) {
    override fun toString(): String = if (variant == null) material else "$material#$variant"

    public companion object {
        @JvmStatic
        public fun of(material: String): ItemKind = ItemKind(material.uppercase())
    }
}
