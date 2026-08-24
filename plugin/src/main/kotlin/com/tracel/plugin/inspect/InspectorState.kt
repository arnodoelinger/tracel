package com.tracel.plugin.inspect

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player inspect-mode toggle.
 *
 * `/tracel inspect` flips it, [com.tracel.plugin.listener.inspect.InspectListener] reads it on
 * every block click.
 */
class InspectorState {
    private val active: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun toggle(player: UUID): Boolean =
        if (active.remove(player)) false else { active.add(player); true }

    fun isActive(player: UUID): Boolean = player in active

    fun clear(player: UUID) {
        active.remove(player)
    }
}
