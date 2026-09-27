package com.tracel.plugin.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.material.MaterialRestorer
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.Location
import kotlin.coroutines.resume

/** Global-region delay so a client tracker can catch a spawn packet. */
internal suspend fun MaterialRestorer.awaitTicks(ticks: Long) {
    suspendCancellableCoroutine { cont ->
        Bukkit.getGlobalRegionScheduler().runDelayed(services.plugin, {
            if (cont.isActive) cont.resume(Unit)
        }, ticks)
    }
}

/** Same as [awaitTicks], but scheduled on the region that owns [holder] instead of the global region. */
internal suspend fun MaterialRestorer.awaitHolderTicks(holder: HolderId.Block, ticks: Long) {
    val world = worldOf(holder.world) ?: return
    val loc = Location(world, holder.x.toDouble(), holder.y.toDouble(), holder.z.toDouble())
    suspendCancellableCoroutine { cont ->
        Bukkit.getRegionScheduler().runDelayed(services.plugin, loc, {
            if (cont.isActive) cont.resume(Unit)
        }, ticks)
    }
}
