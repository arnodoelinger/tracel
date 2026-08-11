package com.tracel.model.item

/**
 * Hash of a decorated item's serialized components (enchantments, custom name,
 * custom model data, ...). Stored as hex text rather than a raw `ByteArray` on
 * purpose: `ByteArray` compares by identity, not content, which would silently
 * break every equality check a `data class` relies on.
 */
@JvmInline
public value class ContentHash(public val hex: String)
