package com.tracel.plugin.listener.session

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** Per-player inspect toggle for [InspectListener]. */
class InspectorState {
    private val active: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Toggle inspector. */
    fun toggle(player: UUID): Boolean =
        if (active.remove(player)) false else { active.add(player); true }

    /** Whether inspector is active. */
    fun isActive(player: UUID): Boolean = player in active

    /** Clear inspector state. */
    fun clear(player: UUID) {
        active.remove(player)
    }
}
