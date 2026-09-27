package com.tracel.storage.ports.ledger

import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import java.lang.foreign.ValueLayout.JAVA_BYTE

/**
 * The bytes behind an [ContentHash]. What a decorated item actually is.
 *
 * An [ItemKey] answers "are these two stacks interchangeable", and a hash
 * is a complete answer to that
 *
 * It is no answer at all to "put it back", which, of course, is the question a rollback asks:
 * reconstructing from a hash is not possible, so a restored enchanted sword just came
 * back as a plain sword and a written book came back blank. The text was never anywhere.
 *
 * So the serialized form is kept beside the hash, written once the first time that item is seen
 * and read only when something has to be rebuilt
 *
 * One row per distinct decorated item that has ever existed on the server, which is a small
 * number — the same sword enchanted the same way is the same row however many people own one.
 */
class ItemForms(private val storage: TracelStorage) {
    /** The same for a whole batch, as one commit. */
    suspend fun rememberAll(forms: Map<ContentHash, ByteArray>) {
        if (forms.isEmpty()) return
        storage.write {
            for ((hash, bytes) in forms) {
                val key = Keys.itemForm(hash.digest())
                if (get(key) == null) put(key, bytes)
            }
        }
    }

    /** The serialized item behind [hash], or null if this store never saw one. */
    suspend fun find(hash: ContentHash): ByteArray? = storage.read {
        get(Keys.itemForm(hash.digest()))?.toArray(JAVA_BYTE)
    }
}

private fun ContentHash.digest(): ByteArray {
    val text = hex
    return ByteArray(text.length / 2) { i ->
        ((Character.digit(text[i * 2], 16) shl 4) or Character.digit(text[i * 2 + 1], 16)).toByte()
    }
}
