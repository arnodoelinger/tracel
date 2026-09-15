package com.tracel.plugin.listener.support

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Suppress capture mint while we spawn our own "Move", or the entity is double-credited. */
class SelfManagedSpawnGuard {
    private val active = ThreadLocal.withInitial { false } // Nested spawn is same thread; Folia region-local
    private val tracked = ConcurrentHashMap.newKeySet<UUID>()

    /** Run [action] with spawn capture off. */
    fun <T> whileSpawning(action: () -> T): T {
        active.set(true)
        try {
            return action()
        } finally {
            active.set(false)
        }
    }

    /**
     * True inside [whileSpawning] on this thread.
     *
     * `Folia`: the region that is spawning).
     */
    val isSelfManagedSpawn: Boolean get() = active.get()

    /**
     * This ground UUID is already on the ledger.
     *
     * Keep it until pickup / merge / despawn: a blast merge swaps UUID and
     * a later spawn would mint the survivor.
     */
    fun track(uuid: UUID) {
        tracked.add(uuid)
    }

    /** Drop the mark; the entity is gone or no longer ours to special-case. */
    fun forget(uuid: UUID) {
        tracked.remove(uuid)
    }

    /** [track]ed and still live. Pickup / merge skip a second credit. */
    fun isTracked(uuid: UUID): Boolean = tracked.contains(uuid)
}
