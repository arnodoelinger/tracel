package com.tracel.plugin.listener

import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import org.bukkit.Material
import org.bukkit.entity.Creeper
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent

/**
 * Feeds [RedstoneTriggerTracker] the two direct explosion triggers [ExplosionCaptureListener]
 * attributes: a button / lever / pressure-plate press (by a player or a mob — [HolderId.Entity]
 * exists precisely for "not a player, still a specific actor"), and a creeper lit by hand with
 * flint and steel.
 */
class RedstoneTriggerListener(private val services: TracelServices) : Listener {
    /**
     * Only player presses reach here — a non-player entity stepping on a plate is already covered by
     * [onEntityInteract], and recording both would just be redundant.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val isPress = when (event.action) {
            Action.RIGHT_CLICK_BLOCK -> block.type.isButtonOrLever()
            Action.PHYSICAL -> block.type.isPressurePlate()
            else -> false
        }
        if (!isPress) return
        services.redstoneTriggers.recordPress(block.world, block.x, block.y, block.z, HolderId.Player(event.player.uniqueId))
    }

    /**
     * Only non-player entities reach here — a player stepping on a plate is already covered by
     * [onPlayerInteract]'s `Action.PHYSICAL` branch, and recording both would just be redundant.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityInteract(event: EntityInteractEvent) {
        if (event.entity is Player) return
        if (!event.block.type.isPressurePlate()) return
        val block = event.block
        services.redstoneTriggers.recordPress(block.world, block.x, block.y, block.z, HolderId.Entity(event.entity.uniqueId))
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
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val creeper = event.rightClicked as? Creeper ?: return
        val item = event.player.inventory.getItem(event.hand)
        if (item.type != Material.FLINT_AND_STEEL) return
        services.redstoneTriggers.recordCreeperIgnition(creeper.uniqueId, HolderId.Player(event.player.uniqueId))
    }

    private fun Material.isButtonOrLever(): Boolean = this == Material.LEVER || name.endsWith("_BUTTON")
    private fun Material.isPressurePlate(): Boolean = name.endsWith("_PRESSURE_PLATE")
}
