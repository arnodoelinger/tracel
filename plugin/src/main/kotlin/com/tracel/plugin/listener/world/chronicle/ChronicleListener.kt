package com.tracel.plugin.listener.world.chronicle

import com.tracel.annotations.Observes
import com.tracel.engine.world.Chronicle
import com.tracel.model.world.WorldId
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.launch
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent

/**
 * The server's own working set, handed to the chronicle: a chunk that unloads will not change until
 * it loads again, so its capsule can be filed now; a chunk that loads is where someone is about to look,
 * so its history is pulled close.
 *
 * Neither waits on the event thread.
 */
internal class ChronicleListener(services: TracelServices, private val chronicle: Chronicle) : TracelListener(services) {
    @Observes
    fun onUnload(event: ChunkUnloadEvent) {
        val world = WorldId(event.world.uid)
        val chunk = event.chunk
        val x = chunk.x
        val z = chunk.z
        services.scope.launch { chronicle.quiet(world, x, z) }
    }

    @Observes
    fun onLoad(event: ChunkLoadEvent) {
        if (event.isNewChunk) return
        val world = WorldId(event.world.uid)
        val chunk = event.chunk
        val x = chunk.x
        val z = chunk.z
        services.scope.launch { chronicle.warm(world, x, z) }
    }
}
