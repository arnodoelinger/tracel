package com.tracel.plugin.importer.coreprotect.translate

import com.tracel.model.item.ItemKey
import com.tracel.model.world.WorldId
import java.util.*

/** What the translator has to ask the server it runs on. */
interface ImportPlatform {
    /** @return the id of the world called [name], or `null` if there is none. */
    fun world(name: String): WorldId?

    /** @return the way this server writes [state], every property spelled out, or `null` if it is not a block here. */
    fun blockState(state: String): String?

    /** @return the ID of the entity type called [name], like `minecraft:zombie`, or `null` if there is none. */
    fun entityType(name: String): String?

    /** @return the player called [name], for a row written before `CoreProtect` kept UUIDs. */
    fun player(name: String): UUID

    /** @return what `CoreProtect` serialized into a `meta` or `metadata` column, or `null` if it cannot be read back. */
    fun decode(blob: ByteArray): List<Any?>?

    /** @return [detail] on a block of [state], the way our own capture would have kept it; `null` if it cannot be built. */
    fun blockExtras(state: String, detail: BlockDetail): ByteArray?

    /** @return the key of an item of [material] carrying [metadata], or `null` if this server has no such item. */
    fun itemKey(material: String, metadata: List<Any?>?): ItemKey?

    /** @return one stack out of a list of them, the way `CoreProtect` keeps a shulker box's contents: its key and how many. */
    fun stack(entry: Any?): Pair<ItemKey, Int>?

    /**
     * @return a mob of [type] at this place, dressed in what `CoreProtect` kept of it when it died, as the snapshot our own
     * capture would have taken; `null` if it cannot be built.
     */
    fun entitySnapshot(world: WorldId, type: String, x: Double, y: Double, z: Double, kept: List<Any?>): ByteArray?
}
