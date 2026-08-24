package com.tracel.storage.intern

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.storage.holder.HolderCodec
import com.tracel.storage.schema.HoldersTable
import com.tracel.storage.schema.ItemKeysTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Interning for [ItemKey] and [HolderId] — shared by every table that references either by id
 * instead of repeating them as text. Every function here must run inside an existing `Exposed`
 * `transaction { }` block; none of them open their own.
 */
object Interning {
    private data class Keyed<T>(val db: Database, val value: T)

    private val itemKeyIds: Cache<Keyed<ItemKey>, Long> = Caffeine.newBuilder().maximumSize(10_000).build()
    private val itemKeysById: Cache<Keyed<Long>, ItemKey> = Caffeine.newBuilder().maximumSize(10_000).build()
    private val holderIds: Cache<Keyed<HolderId>, Long> = Caffeine.newBuilder().maximumSize(50_000).build()
    private val holdersById: Cache<Keyed<Long>, HolderId> = Caffeine.newBuilder().maximumSize(50_000).build()

    private fun currentDb(): Database = TransactionManager.current().db

    /** Finds or creates the interned row for [itemKey], returning its id. */
    fun internItemKey(itemKey: ItemKey): Long {
        val db = currentDb()
        val key = Keyed(db, itemKey)
        itemKeyIds.getIfPresent(key)?.let { return it }
        val id = findItemKeyId(itemKey) ?: (
            ItemKeysTable.insert {
                it[material] = itemKey.material
                it[decoration] = itemKey.decoration?.hex
            } get ItemKeysTable.id
            )
        itemKeyIds.put(key, id)
        itemKeysById.put(Keyed(db, id), itemKey)
        return id
    }

    /**
     * `null` if [itemKey] has never been interned — nothing could reference it yet,
     * so nothing can match it either.
     */
    fun findItemKeyId(itemKey: ItemKey): Long? {
        val db = currentDb()
        val key = Keyed(db, itemKey)
        itemKeyIds.getIfPresent(key)?.let { return it }
        val id = ItemKeysTable.selectAll()
            .where { (ItemKeysTable.material eq itemKey.material) and (ItemKeysTable.decoration eq itemKey.decoration?.hex) }
            .map { it[ItemKeysTable.id] }
            .firstOrNull() ?: return null
        itemKeyIds.put(key, id)
        return id
    }

    fun resolveItemKey(id: Long): ItemKey {
        val db = currentDb()
        val cacheKey = Keyed(db, id)
        itemKeysById.getIfPresent(cacheKey)?.let { return it }
        val itemKey = ItemKeysTable.selectAll().where { ItemKeysTable.id eq id }.single().let {
            ItemKey(it[ItemKeysTable.material], it[ItemKeysTable.decoration]?.let(::ContentHash))
        }
        itemKeysById.put(cacheKey, itemKey)
        itemKeyIds.put(Keyed(db, itemKey), id)
        return itemKey
    }

    /** Finds or creates the interned row for [holder], returning its id. */
    fun internHolder(holder: HolderId): Long {
        val db = currentDb()
        val key = Keyed(db, holder)
        holderIds.getIfPresent(key)?.let { return it }
        val encoded = HolderCodec.encode(holder)
        val id = findHolderId(encoded) ?: (HoldersTable.insert { it[HoldersTable.encoded] = encoded } get HoldersTable.id)
        holderIds.put(key, id)
        holdersById.put(Keyed(db, id), holder)
        return id
    }

    /** `null` if [holder] has never been interned. */
    fun findHolderId(holder: HolderId): Long? {
        val db = currentDb()
        val key = Keyed(db, holder)
        holderIds.getIfPresent(key)?.let { return it }
        return findHolderId(HolderCodec.encode(holder))?.also { holderIds.put(key, it) }
    }

    fun resolveHolder(id: Long): HolderId {
        val db = currentDb()
        val cacheKey = Keyed(db, id)
        holdersById.getIfPresent(cacheKey)?.let { return it }
        val holder = HolderCodec.decode(HoldersTable.selectAll().where { HoldersTable.id eq id }.single()[HoldersTable.encoded])
        holdersById.put(cacheKey, holder)
        holderIds.put(Keyed(db, holder), id)
        return holder
    }

    fun findHolderId(encoded: String): Long? =
        HoldersTable.selectAll().where { HoldersTable.encoded eq encoded }.map { it[HoldersTable.id] }.firstOrNull()

    /** Drops every cached id for [db]. */
    fun forget(db: Database) {
        itemKeyIds.asMap().keys.removeIf { it.db == db }
        itemKeysById.asMap().keys.removeIf { it.db == db }
        holderIds.asMap().keys.removeIf { it.db == db }
        holdersById.asMap().keys.removeIf { it.db == db }
    }
}
