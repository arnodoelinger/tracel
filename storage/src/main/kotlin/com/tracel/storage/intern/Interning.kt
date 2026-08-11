package com.tracel.storage.intern

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.storage.holder.HolderCodec
import com.tracel.storage.schema.HoldersTable
import com.tracel.storage.schema.ItemKeysTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Interning for [ItemKey] and [HolderId] — shared by every table that references either by id
 * instead of repeating them as text. Every function here must run inside an existing Exposed
 * `transaction { }` block; none of them open their own.
 */
object Interning {
    /** Finds or creates the interned row for [itemKey], returning its id. */
    fun internItemKey(itemKey: ItemKey): Long =
        findItemKeyId(itemKey) ?: (
            ItemKeysTable.insert {
                it[material] = itemKey.material
                it[decoration] = itemKey.decoration?.hex
            } get ItemKeysTable.id
            )

    /** `null` if [itemKey] has never been interned — nothing could reference it yet, so nothing can match it either. */
    fun findItemKeyId(itemKey: ItemKey): Long? =
        ItemKeysTable.selectAll()
            .where { (ItemKeysTable.material eq itemKey.material) and (ItemKeysTable.decoration eq itemKey.decoration?.hex) }
            .map { it[ItemKeysTable.id] }
            .firstOrNull()

    fun resolveItemKey(id: Long): ItemKey =
        ItemKeysTable.selectAll().where { ItemKeysTable.id eq id }.single().let {
            ItemKey(it[ItemKeysTable.material], it[ItemKeysTable.decoration]?.let(::ContentHash))
        }

    /** Finds or creates the interned row for [holder], returning its id. */
    fun internHolder(holder: HolderId): Long {
        val encoded = HolderCodec.encode(holder)
        return findHolderId(encoded) ?: (HoldersTable.insert { it[HoldersTable.encoded] = encoded } get HoldersTable.id)
    }

    /** `null` if [holder] has never been interned. */
    fun findHolderId(holder: HolderId): Long? = findHolderId(HolderCodec.encode(holder))

    fun resolveHolder(id: Long): HolderId =
        HolderCodec.decode(HoldersTable.selectAll().where { HoldersTable.id eq id }.single()[HoldersTable.encoded])

    fun findHolderId(encoded: String): Long? =
        HoldersTable.selectAll().where { HoldersTable.encoded eq encoded }.map { it[HoldersTable.id] }.firstOrNull()
}
