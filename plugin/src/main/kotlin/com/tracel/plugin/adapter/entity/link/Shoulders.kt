package com.tracel.plugin.adapter.entity.link

import com.tracel.plugin.adapter.entity.special.ShoulderAdapter
import org.bukkit.Location
import java.util.*

/** Clears [uuid] off any online player's shoulder so a restore can spawn the bird in the world. */
internal fun takeOffShoulder(uuid: UUID, near: Location): Boolean = ShoulderAdapter.takeOff(uuid, near)
