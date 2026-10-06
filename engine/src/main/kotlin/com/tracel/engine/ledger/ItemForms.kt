package com.tracel.engine.ledger

import com.tracel.model.item.ContentHash

/**
 * The bytes behind a [ContentHash]: what a decorated item actually is.
 *
 * A hash says whether two stacks are interchangeable and nothing about how to put one back, so the serialized form is
 * kept beside it, written the first time that item is seen and read only when something has to be rebuilt.
 */
public interface ItemForms {
    /** Keeps the serialized form of every item in [forms], as one commit. */
    public suspend fun rememberAll(forms: Map<ContentHash, ByteArray>)

    /** The serialized item behind [hash], or null if none was ever seen. */
    public suspend fun find(hash: ContentHash): ByteArray?
}
