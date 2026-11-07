package com.tracel.storage.table

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ContentHash
import com.tracel.model.world.BlockPos
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.spi.EngineCursor
import java.util.UUID

/**
 * What a table's key holds, as the fields its [Tag] takes.
 *
 * Writes [intern]; reads and deletes [find], which never makes an ID and
 * answers `null` when one is missing.
 */
interface KeyCodec<K> {
    fun intern(storage: TracelStorage, unit: StorageUnit, key: K): Array<Any>

    fun find(storage: TracelStorage, unit: StorageUnit, key: K): Array<Any>?

    fun read(storage: TracelStorage, unit: StorageUnit, tag: Tag, cursor: EngineCursor): K

    companion object {
        inline fun <reified H : HolderId> holder(): KeyCodec<H> = object : KeyCodec<H> {
            override fun intern(storage: TracelStorage, unit: StorageUnit, key: H): Array<Any> =
                arrayOf(storage.interning.internHolder(unit, key))

            override fun find(storage: TracelStorage, unit: StorageUnit, key: H): Array<Any>? =
                storage.interning.findHolderId(unit, key)?.let { arrayOf(it) }

            override fun read(storage: TracelStorage, unit: StorageUnit, tag: Tag, cursor: EngineCursor): H =
                storage.interning.resolveHolder(unit, tag.int(cursor, 0)) as H
        }

        val uuid: KeyCodec<UUID> = object : KeyCodec<UUID> {
            override fun intern(storage: TracelStorage, unit: StorageUnit, key: UUID): Array<Any> = arrayOf(key)

            override fun find(storage: TracelStorage, unit: StorageUnit, key: UUID): Array<Any> = arrayOf(key)

            override fun read(storage: TracelStorage, unit: StorageUnit, tag: Tag, cursor: EngineCursor): UUID = tag.uuid(cursor, 0)
        }

        val blockPos: KeyCodec<BlockPos> = object : KeyCodec<BlockPos> {
            override fun intern(storage: TracelStorage, unit: StorageUnit, key: BlockPos): Array<Any> =
                arrayOf(storage.interning.internWorld(unit, key.world), key.x, key.y, key.z)

            override fun find(storage: TracelStorage, unit: StorageUnit, key: BlockPos): Array<Any>? =
                storage.interning.findWorldId(unit, key.world)?.let { arrayOf(it, key.x, key.y, key.z) }

            override fun read(storage: TracelStorage, unit: StorageUnit, tag: Tag, cursor: EngineCursor): BlockPos = BlockPos(
                storage.interning.resolveWorld(unit, tag.int(cursor, 0)),
                tag.int(cursor, 1),
                tag.int(cursor, 2),
                tag.int(cursor, 3),
            )
        }

        val hash: KeyCodec<ContentHash> = object : KeyCodec<ContentHash> {
            override fun intern(storage: TracelStorage, unit: StorageUnit, key: ContentHash): Array<Any> = arrayOf(digest(key))

            override fun find(storage: TracelStorage, unit: StorageUnit, key: ContentHash): Array<Any> = arrayOf(digest(key))

            override fun read(storage: TracelStorage, unit: StorageUnit, tag: Tag, cursor: EngineCursor): ContentHash =
                ContentHash(tag.raw(cursor.key(), 0).joinToString("") { "%02x".format(it) })

            private fun digest(hash: ContentHash): ByteArray {
                val text = hash.hex
                return ByteArray(text.length / 2) { i ->
                    ((Character.digit(text[i * 2], 16) shl 4) or Character.digit(text[i * 2 + 1], 16)).toByte()
                }
            }
        }
    }
}
