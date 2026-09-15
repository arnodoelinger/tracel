package com.tracel.plugin.listener.world.entity

import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.TracelListener
import org.bukkit.Material
import org.bukkit.entity.Creeper
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent

/**
 * Redstone listener.
 *
 * Records press and flint-and-steel creeper ignition for TNT / creeper explosion
 * attribution too.
 */
@Unstable
class RedstoneListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onPlace(event: BlockPlaceEvent) {
        if (event is BlockMultiPlaceEvent) return
        if (!event.block.type.canPrimeTnt()) return
        val block = event.block
        services.redstoneTriggers.recordPress(
            block.world, block.x, block.y, block.z,
            HolderId.Player(event.player.uniqueId),
        )
    }

    @Observes
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val isPress = when (event.action) {
            Action.RIGHT_CLICK_BLOCK -> block.type.isButtonOrLever()
            Action.PHYSICAL -> block.type.isPressurePlate()
            else -> false
        }
        if (!isPress) return
        services.redstoneTriggers.recordPress(
            block.world,
            block.x,
            block.y,
            block.z,
            HolderId.Player(event.player.uniqueId)
        )
    }

    @Observes
    fun onEntityInteract(event: EntityInteractEvent) {
        if (event.entity is Player) return
        if (!event.block.type.isPressurePlate()) return
        val block = event.block
        services.redstoneTriggers.recordPress(
            block.world,
            block.x,
            block.y,
            block.z,
            HolderId.Entity(event.entity.uniqueId)
        )
    }

    /**
     * ⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀
     * ⠀⠀⠀⠀⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀
     * ⠀⠀⠀⠀⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀
     * ⠀⠀⠀⠀⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀
     * ⠀⠀⠀⠀⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀
     * ⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀
     * ⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀
     * ⠀⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀
     * ⠀⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀
     * ⠀⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀
     * ⠀⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀
     * ⠀⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀
     * ⠀⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⡇⠀⠀
     */
    @Observes
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val creeper = event.rightClicked as? Creeper ?: return
        val item = event.player.inventory.getItem(event.hand)
        if (item.type != Material.FLINT_AND_STEEL) return
        services.redstoneTriggers.recordCreeperIgnition(creeper.uniqueId, HolderId.Player(event.player.uniqueId))
    }

    private fun Material.isButtonOrLever(): Boolean = this == Material.LEVER || name.endsWith("_BUTTON")
    private fun Material.isPressurePlate(): Boolean = name.endsWith("_PRESSURE_PLATE")
    private fun Material.canPrimeTnt(): Boolean = when (this) {
        Material.REDSTONE_BLOCK,
        Material.OBSERVER,
        Material.LEVER,
        Material.REDSTONE_TORCH,
        Material.REDSTONE_WALL_TORCH,
        Material.TRIPWIRE_HOOK,
            -> true

        else -> name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE")
    }
}
