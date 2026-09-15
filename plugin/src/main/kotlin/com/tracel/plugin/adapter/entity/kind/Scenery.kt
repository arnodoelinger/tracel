package com.tracel.plugin.adapter.entity.kind

import com.tracel.annotations.Unstable
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Boat
import org.bukkit.entity.Display
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Hanging
import org.bukkit.entity.Interaction
import org.bukkit.entity.Minecart
import org.bukkit.entity.TNTPrimed

/** Player-created scenery. */
@Unstable
internal object Scenery {
    private val all: List<HullMatch> = listOf(
        HullMatch { it is Hanging },
        HullMatch { it is ArmorStand },
        HullMatch { it is Boat },
        HullMatch { it is Minecart },
        HullMatch { it is EnderCrystal },
        HullMatch { it is FallingBlock },
        HullMatch { it is Display },
        HullMatch { it is Interaction },
        HullMatch { it is TNTPrimed },
        HullMatch { it is AbstractArrow && it.isReclaimable() },
    )

    /** True if [entity] is player-placed scenery the world log should keep. */
    fun matches(entity: Entity): Boolean = all.any { it.matches(entity) }
}
