package com.tracel.plugin.listener.capture

/**
 * A release that takes manual control of its own drops needs [ItemEntityCaptureListener] to stay
 * out of the way for those specific spawns, or it would independently mint the entity the release
 * is about to credit as a `MOVE`, double-crediting it.
 *
 * `World.dropItemNaturally(...)` fires `ItemSpawnEvent` synchronously and nested, so a flag set
 * immediately before the call and cleared immediately after is enough to mark exactly those spawns.
 */
class SelfManagedSpawnGuard {
    private val active = ThreadLocal.withInitial { false } // Synchronous and single-threaded

    fun <T> whileSpawning(action: () -> T): T {
        active.set(true)
        try {
            return action()
        } finally {
            active.set(false)
        }
    }

    val isSelfManagedSpawn: Boolean get() = active.get()
}
