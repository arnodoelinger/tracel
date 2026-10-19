package com.tracel.plugin.listener.support.flow

import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.item.ItemKey
import org.bukkit.GameMode
import org.bukkit.entity.Player

/** Creative source. */
val CREATIVE_SOURCE: HolderId.Source = HolderId.Source(SourceKind.CREATIVE)

/** Creative sink. */
val CREATIVE_SINK: HolderId.Sink = HolderId.Sink(SinkKind.CREATIVE)

/**
 * Spectator inventories are unused.
 *
 * Creative is a real holder: treating it otherwise emptied chests on rollback and
 * compensated nobody (menu mint / drag-off burn stay on the player).
 */
fun Player.isLedgeredHolder(): Boolean = gameMode != GameMode.SPECTATOR

/**
 * What a creative click put into a cargo tile out of thin air.
 *
 * Creative keeps what it puts in, so the gain pairs with nobody. Booked as a plain
 * gain on a tile the differ has not seen yet, it became a gap correction, and rollback
 * never takes those out: the book stayed in the lectern for good.
 *
 * A swap paid with what it took out and pairs like any move.
 *
 * @return what the tile gained, or nothing if it also gave something up.
 */
fun creativeGains(before: Map<ItemKey, Long>, after: Map<ItemKey, Long>): Map<ItemKey, Long> {
    if (before.any { (itemKey, qty) -> (after[itemKey] ?: 0L) < qty }) return emptyMap()
    return after.mapNotNull { (itemKey, qty) ->
        (qty - (before[itemKey] ?: 0L)).takeIf { it > 0L }?.let { itemKey to it }
    }.toMap()
}
