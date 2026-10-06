package com.tracel.model.item

/**
 * What makes two item stacks interchangeable.
 *
 * Plain items use only their material. Items with relevant components also include a
 * [decoration] hash, so differently decorated items remain distinct. Wear is not part of it.
 */
public data class ItemKey(
    public val material: String,
    public val decoration: ContentHash? = null,
)
