package com.tracel.plugin.adapter.entity.kind

import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

/**
 * What the world log does with this hull.
 *
 * @see [Entity]
 */
enum class WorldHull {
    /** Players and ground items. */
    Skip,

    /** Placed scenery. Logged whoever put it there. */
    Scenery,

    /** Mobs. Logged only when a person made them. */
    Creature,
}

/** How the world log treats this hull. Players and ground items are never a shape. */
fun Entity.worldHull(): WorldHull = when {
    this is Player || this is Item -> WorldHull.Skip
    Scenery.matches(this) -> WorldHull.Scenery
    this is LivingEntity -> WorldHull.Creature
    else -> WorldHull.Skip
}

/** Placed scenery: boats, hangings, TNT, displays. Wildlife is not. */
fun Entity.isScenery(): Boolean = worldHull() == WorldHull.Scenery

/**
 * Whether a spawn or removal of this hull can be a world-log row.
 *
 * False for players and dropped items — those are ledger material, not scenery.
 */
fun Entity.logsWorldShape(): Boolean = worldHull() != WorldHull.Skip
