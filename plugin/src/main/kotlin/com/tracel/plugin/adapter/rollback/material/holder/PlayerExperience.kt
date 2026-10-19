package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.plugin.adapter.rollback.material.item.Moves
import com.tracel.plugin.specifics.item.Experience
import org.bukkit.entity.Player

/**
 * Gives or takes [delta] experience points, no mending.
 *
 * What the player has already spent cannot be taken back: that part is named short.
 */
internal fun Player.shiftExperience(delta: Long, moves: Moves) {
    if (delta > 0L) {
        giveExp(delta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), false)
        return
    }
    val have = calculateTotalExperiencePoints().toLong()
    val taken = minOf(have, -delta)
    if (taken > 0L) setExperienceLevelAndProgress((have - taken).toInt())
    moves.short(Experience.KEY, -delta - taken)
}
