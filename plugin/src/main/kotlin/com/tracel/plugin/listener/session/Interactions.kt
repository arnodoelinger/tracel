package com.tracel.plugin.listener.session

import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractEvent

/** The click does nothing in the world: no block is used, no item. */
internal fun PlayerInteractEvent.denyUse() {
    setUseInteractedBlock(Event.Result.DENY)
    setUseItemInHand(Event.Result.DENY)
}
