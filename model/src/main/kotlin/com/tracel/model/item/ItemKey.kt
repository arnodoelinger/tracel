package com.tracel.model.item

/**
 * What makes two item stacks interchangeable.
 *
 * Plain items use only their material. Items with relevant components also include a
 * [decoration] hash, so differently enchanted or damaged items remain distinct.
 */
public data class ItemKey(
    public val material: String,
    public val decoration: ContentHash? = null,
)

/**
 * Checks whether this value refers to [wanted] material, with or without a namespace.
 *
 * Item keys store plain material names, while world log entries may include a namespace.
 */
public fun String.namesMaterial(wanted: String): Boolean =
    equals(wanted, ignoreCase = true) || equals(wanted.substringAfter(':'), ignoreCase = true)
