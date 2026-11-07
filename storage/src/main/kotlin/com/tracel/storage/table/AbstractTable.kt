package com.tracel.storage.table

import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag

/**
 * One key family as a table: a [Tag] that lays the key out,
 * and a [KeyCodec] that fills it in.
 */
abstract class AbstractTable<K>(
    protected val storage: TracelStorage,
    protected val tag: Tag,
    protected val keys: KeyCodec<K>,
) {
    /** The row key of [key], interning whatever it names. */
    protected fun StorageUnit.keyOf(key: K): ByteArray = tag.get(*keys.intern(storage, this, key))

    /** The row key of [key], or `null` when something it names has no id yet, so no row can exist. */
    protected fun StorageUnit.foundKey(key: K): ByteArray? = keys.find(storage, this, key)?.let { tag.get(*it) }
}
