package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.model.holder.HolderId
import com.tracel.model.world.WorldId
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.services.TracelServices
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.EntitiesUnloadEvent

/**
 * Drops inventory snapshots of holders that left memory. Without it the differ keeps one per
 * chest and player ever seen; the next look at a returning holder starts from the ledger instead.
 */
class SnapshotEvictionListener(services: TracelServices) : TracelListener(services) {
    private val queued = ConcurrentLinkedQueue<HolderId>()
    private val draining = AtomicBoolean()

    @Observes(ignoreCancelled = false)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        forgetLater(listOf(HolderId.Player(uuid), HolderId.PlayerStash(uuid)))
    }

    @Observes(ignoreCancelled = false)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val tiles = event.chunk.getTileEntities(false)
        if (tiles.isEmpty()) return
        val world = WorldId(event.world.uid)
        forgetLater(tiles.flatMap { tile ->
            listOfNotNull(
                HolderId.Block(world, tile.x, tile.y, tile.z),
                runCatching { tile.block.toHolderId() }.getOrNull()
            )
        })
    }

    @Observes(ignoreCancelled = false)
    fun onEntitiesUnload(event: EntitiesUnloadEvent) {
        if (event.entities.isEmpty()) return
        forgetLater(event.entities.map { HolderId.Entity(it.uniqueId) })
    }

    private fun forgetLater(holders: List<HolderId>) {
        queued += holders
        if (!draining.compareAndSet(false, true)) return
        services.scope.launch {
            try {
                while (queued.isNotEmpty()) {
                    val batch = ArrayList<HolderId>()
                    while (true) batch += queued.poll() ?: break
                    services.pendingCaptures.await()
                    for (holder in batch) services.differ.forget(holder)
                }
            } finally {
                draining.set(false)
                if (queued.isNotEmpty()) forgetLater(emptyList())
            }
        }
    }
}
