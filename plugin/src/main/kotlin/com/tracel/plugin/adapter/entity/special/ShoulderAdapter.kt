package com.tracel.plugin.adapter.entity.special

import com.tracel.annotations.Unstable
import java.util.*
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.HumanEntity

/**
 * Entity on a shoulder (like parrots on vanilla).
 *
 * @see HumanEntity
 */
@Unstable
internal object ShoulderAdapter {
    private const val SEARCH_RADIUS = 48.0

    @Suppress("DEPRECATION") // Yes, shoulderEntityX is deprecated and it's strange
    fun takeOff(uuid: UUID, near: Location): Boolean {
        val world = near.world ?: return false
        for (player in world.getNearbyPlayers(near, SEARCH_RADIUS)) {
            if (!Bukkit.isOwnedByCurrentRegion(player)) continue
            if (runCatching { player.shoulderEntityLeft?.uniqueId }.getOrNull() == uuid) {
                runCatching { player.shoulderEntityLeft = null }
                return true
            }
            if (runCatching { player.shoulderEntityRight?.uniqueId }.getOrNull() == uuid) {
                runCatching { player.shoulderEntityRight = null }
                return true
            }
        }
        return false
    }
}
