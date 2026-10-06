package com.tracel.plugin.specifics.entity

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.entity.kind.HullMatch
import com.tracel.plugin.adapter.entity.kind.isReclaimable
import org.bukkit.entity.*

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
        HullMatch { it.type.key.key.contains("cushion") },
    )

    /** True if [entity] is player-placed scenery the world log should keep. */
    fun matches(entity: Entity): Boolean = all.any { it.matches(entity) }
}
