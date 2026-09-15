package com.tracel.plugin.adapter.entity.special

import com.tracel.annotations.Unstable
import org.bukkit.entity.HumanEntity
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Entity on a shoulder (like parrots on vanilla).
 *
 * @see HumanEntity
 */
@Unstable
internal object ShoulderAdapter {
    @Suppress("DEPRECATION") // Yes, shoulderEntityX is deprecated and it's strange
    fun takeOff(uuid: UUID): Boolean {
        for (player in Bukkit.getOnlinePlayers()) {
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
