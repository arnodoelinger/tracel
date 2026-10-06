package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.plugin.rollback.material.MaterialRestorer
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import kotlin.coroutines.resume

/** Global-region delay so a client tracker can catch a spawn packet. */
internal suspend fun MaterialRestorer.awaitTicks(ticks: Long) {
    suspendCancellableCoroutine { cont ->
        Bukkit.getGlobalRegionScheduler().runDelayed(services.plugin, {
            if (cont.isActive) cont.resume(Unit)
        }, ticks)
    }
}
