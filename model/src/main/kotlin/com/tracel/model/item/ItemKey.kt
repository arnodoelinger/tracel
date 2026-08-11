package com.tracel.model.item

/**
 * What makes two item stacks interchangeable.
 *
 * Plain items are identified by material alone: [decoration] is `null`. Only items
 * with components that affect interchangeability (enchantments, a custom name,
 * custom model data) carry a [decoration] hash, so two enchanted swords with
 * different enchants are never treated as the same item key.
 */
public data class ItemKey(
    public val material: String,
    public val decoration: ContentHash? = null,
)
