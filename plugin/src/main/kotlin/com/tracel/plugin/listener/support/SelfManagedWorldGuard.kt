package com.tracel.plugin.listener.support

/**
 * Restore must not log itself, that's why we need self managed world guard. Putting a wall back fires
 * the same events as building one; if those land in the store, the next rollback over the same window
 * undoes the repair.
 *
 * Every restorer write is nested in [whileRestoring] on the region thread; listeners check
 * [isRestoring] first. Same thread-local as [SelfManagedSpawnGuard]: events are synchronous and
 * nested, so the flag marks exactly those writes.
 */
class SelfManagedWorldGuard {
    private val active = ThreadLocal.withInitial { false }

    fun <T> whileRestoring(action: () -> T): T {
        val previous = active.get()
        active.set(true)
        try {
            return action()
        } finally {
            active.set(previous)
        }
    }

    val isRestoring: Boolean get() = active.get()
}
