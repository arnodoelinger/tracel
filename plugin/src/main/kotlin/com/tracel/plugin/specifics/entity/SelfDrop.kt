package com.tracel.plugin.specifics.entity

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.entity.kind.HullMatch
import org.bukkit.entity.*

/** Hangings, stands, boats, minecarts, etc. that drops themselves as items. */
@Unstable
internal object SelfDrop {
    private val all: List<HullMatch> = listOf(
        HullMatch { it is Hanging },
        HullMatch { it is ArmorStand },
        HullMatch { it is Boat },
        HullMatch { it is Minecart },
        HullMatch { it.type.key.key.contains("cushion") },
    )

    /** True if breaking [entity] drops the hull as an item, not just its cargo. */
    fun matches(entity: Entity): Boolean = all.any { it.matches(entity) }
}

/** Whether vanilla drops this hull as an item — a boat, a frame, a stand, a minecart. */
fun Entity.dropsSelf(): Boolean = SelfDrop.matches(this)

/**
 * Whether this hull's cargo is ours to withhold from vanilla drops.
 *
 * Same set as [dropsSelf].
 */
fun Entity.dropsManagedCargo(): Boolean = dropsSelf()
