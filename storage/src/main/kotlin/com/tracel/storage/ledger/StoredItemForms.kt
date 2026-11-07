package com.tracel.storage.ledger

import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.table.KeyCodec
import com.tracel.storage.table.StoredKeyedTable
import com.tracel.storage.table.ValueCodec

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
class StoredItemForms(storage: TracelStorage) :
    StoredKeyedTable<ContentHash, ByteArray>(storage, Tag.ITEM_FORM, KeyCodec.hash, ValueCodec.bytes, keepFirst = true)
