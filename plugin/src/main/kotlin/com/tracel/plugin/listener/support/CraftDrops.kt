package com.tracel.plugin.listener.support

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.util.ExpiringMap
import java.util.UUID

/** Crafts thrown straight from the result slot (Q, ctrl-Q). */
internal object CraftDrops {
    data class Thrown(val pile: HolderId.ItemEntity, val itemKey: ItemKey, val quantity: Long)

    private const val TTL_MS = 2_000L

    private val expecting = ExpiringMap<UUID, Boolean>(TTL_MS, 4_096)
    private val thrown = ExpiringMap<UUID, List<Thrown>>(TTL_MS, 4_096)

    fun expect(player: UUID) {
        expecting.put(player, true)
    }

    fun expecting(player: UUID): Boolean = expecting[player] == true

    fun add(player: UUID, pile: Thrown) {
        thrown.put(player, thrown[player].orEmpty() + pile)
    }

    fun take(player: UUID): List<Thrown> {
        expecting.remove(player)
        return thrown.remove(player).orEmpty()
    }
}
