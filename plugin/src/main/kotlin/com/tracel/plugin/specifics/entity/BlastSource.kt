package com.tracel.plugin.specifics.entity

import org.bukkit.entity.Creeper
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Entity
import org.bukkit.entity.Explosive
import org.bukkit.entity.Ghast

/** Whether it's a blast source, i.e. a source of explosion damage. */
internal fun Entity?.isBlastSource(): Boolean = when (this) {
    is Explosive, is Creeper, is EnderCrystal, is Ghast -> true
    else -> false
}
