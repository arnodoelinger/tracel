package com.tracel.plugin.specifics.item

import com.tracel.model.item.ItemKey
import org.bukkit.entity.Entity
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Item

/** Experience points, booked as an item that is no material. */
internal object Experience {
    val KEY = ItemKey("EXPERIENCE")

    fun isExperience(key: ItemKey): Boolean = key == KEY
}

/** Whether this is something the ledger keeps a ground pile for: a dropped item or an orb. */
internal fun Entity?.isGroundPile(): Boolean = this is Item || this is ExperienceOrb
