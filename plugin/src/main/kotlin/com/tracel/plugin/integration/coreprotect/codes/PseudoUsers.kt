package com.tracel.plugin.integration.coreprotect.codes

import com.tracel.annotations.Unstable

/** `#names` CoreProtect blames an explosion on. */
@Unstable
internal val EXPLOSIVE = setOf(
    "tnt", "creeper", "explosion", "end_crystal", "ender_crystal", "wither", "wither_skull", "fireball",
    "ghast", "tnt_minecart", "bed", "respawn_anchor", "enderdragon", "ender_dragon", "wind_charge",
)

/** Old `#names` for mobs, and what the game calls them now. */
@Unstable
internal val MOB_ALIASES = mapOf("enderdragon" to "ender_dragon", "ender_crystal" to "end_crystal")
