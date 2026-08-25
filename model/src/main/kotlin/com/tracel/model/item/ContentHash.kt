package com.tracel.model.item

/**
 * Hash of a decorated item's serialized components (enchantments, custom name,
 * custom model data, ...). Stored as hex text on purpose: `ByteArray` compares
 * by identity, not content, which would silently break every equality check a
 * `data class` relies on.
 */
@JvmInline
public value class ContentHash(public val hex: String)
