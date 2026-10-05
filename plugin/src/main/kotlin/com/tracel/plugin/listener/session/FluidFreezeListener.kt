package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.model.id.WorldId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.TracelListener
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.Cancellable
import org.bukkit.event.block.*

/**
 * Keeps fluids out of the cells a running rollback or undo holds: no flow into, out of or inside them, nothing formed
 * or faded there, no sponge drinking from them.
 */
class FluidFreezeListener(services: TracelServices) : TracelListener(services) {
    @Observes(priority = Priority.LOWEST)
    fun holdFlow(event: BlockFromToEvent) {
        if (event.block.type == Material.DRAGON_EGG) return
        if (held(event.block) || held(event.toBlock)) stop(event, event.block)
    }

    @Observes(priority = Priority.LOWEST)
    fun holdLevel(event: FluidLevelChangeEvent) {
        if (held(event.block)) stop(event, event.block)
    }

    @Observes(priority = Priority.LOWEST)
    fun holdForm(event: BlockFormEvent) {
        if (event is EntityBlockFormEvent) return
        if (held(event.block)) stop(event, event.block)
    }

    @Observes(priority = Priority.LOWEST)
    fun holdFade(event: BlockFadeEvent) {
        if (held(event.block)) stop(event, event.block)
    }

    @Observes(priority = Priority.LOWEST)
    fun holdSponge(event: SpongeAbsorbEvent) {
        if (held(event.block)) {
            event.isCancelled = true
            return
        }
        event.blocks.removeIf { held(it.block) }
        if (event.blocks.isEmpty()) event.isCancelled = true
    }

    private fun held(block: Block): Boolean =
        services.fluidFreeze.holds(WorldId(block.world.uid), block.x, block.y, block.z)

    private fun stop(event: Cancellable, moving: Block) {
        event.isCancelled = true
        services.fluidFreeze.stir(WorldId(moving.world.uid), moving.x, moving.y, moving.z)
    }
}
