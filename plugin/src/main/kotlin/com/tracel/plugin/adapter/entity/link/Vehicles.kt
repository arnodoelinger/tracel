package com.tracel.plugin.adapter.entity.link

import com.tracel.annotations.Unstable
import java.util.*
import org.bukkit.Bukkit
import org.bukkit.entity.Entity

/** The vehicle this entity is sitting in. `null` if it is standing. */
internal fun Entity.ridingOn(): Entity? = runCatching { vehicle }.getOrNull()

/**
 * Puts this entity on [vehicle], or dismounts when [vehicle] is `null`.
 *
 * `false` if the mount is not loaded yet. Capture is suppressed via [SelfManagedLink].
 */
@Unstable
internal fun Entity.applyVehicle(vehicle: UUID?): Boolean {
    val riding = runCatching { this.vehicle?.uniqueId }.getOrNull()
    if (riding == vehicle) return true
    if (vehicle == null) {
        SelfManagedLink.whileLinking { runCatching { leaveVehicle() } }
        return true
    }
    val mount = Bukkit.getEntity(vehicle)?.takeIf { it.isValid } ?: return false
    return SelfManagedLink.whileLinking {
        if (riding != null) runCatching { leaveVehicle() }
        runCatching { mount.addPassenger(this) }.getOrDefault(false)
    }
}
