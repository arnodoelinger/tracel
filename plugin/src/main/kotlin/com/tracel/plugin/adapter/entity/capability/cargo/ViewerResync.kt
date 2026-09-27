package com.tracel.plugin.adapter.entity.capability.cargo

import com.tracel.annotations.Unstable
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Hide, then show the entity, so clients notice metadata the server already applied. */
@Unstable
internal fun Entity.hideThenShow(plugin: Plugin) {
    val viewers = viewersForCargo()
    for (player in viewers) runCatching { player.hideEntity(plugin, this) }
    scheduler.runDelayed(plugin, {
        if (!isValid) return@runDelayed
        for (player in viewersForCargo()) runCatching { player.showEntity(plugin, this) }
    }, {}, 1L)
}

/** Players who already have this entity. Empty means nobody to resync. */
internal fun Entity.viewersForCargo(): Collection<Player> =
    runCatching { trackedBy }.getOrDefault(emptySet())
