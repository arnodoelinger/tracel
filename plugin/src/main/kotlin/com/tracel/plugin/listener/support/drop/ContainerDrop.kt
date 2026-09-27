package com.tracel.plugin.listener.support.drop

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.util.ExpiringMap
import java.util.*

/** Temporary storage for items dropped directly from container slots. */
internal object ContainerDrop {
    data class Pending(val from: HolderId, val itemKey: ItemKey)

    private const val TTL_MS = 1_000L

    private val byPlayer = ExpiringMap<UUID, Pending>(TTL_MS, 4_096)

    fun expect(player: UUID, from: HolderId, itemKey: ItemKey) {
        byPlayer.put(player, Pending(from, itemKey))
    }

    fun take(player: UUID, itemKey: ItemKey): HolderId? {
        val pending = byPlayer[player] ?: return null
        if (pending.itemKey != itemKey) return null
        byPlayer.remove(player)
        return pending.from
    }
}
