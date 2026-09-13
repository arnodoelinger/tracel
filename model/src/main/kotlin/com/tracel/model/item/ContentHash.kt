package com.tracel.model.item

/**
 * Hash of a decorated item's serialized components (enchantments, custom name,
 * custom model data, etc.).
 *
 * Stored as hex text.
 */
@JvmInline
public value class ContentHash(public val hex: String)
