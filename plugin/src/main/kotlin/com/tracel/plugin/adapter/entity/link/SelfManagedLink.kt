package com.tracel.plugin.adapter.entity.link

/**
 * Marks a leash or mount change as ours, so listeners do not write a second world-log row
 * for a restore that is already applying the recorded link.
 */
internal object SelfManagedLink {
    private val active = ThreadLocal.withInitial { false }

    /** True on the thread that is currently applying a recorded leash or mount. */
    val isOurs: Boolean get() = active.get()

    /** Runs [action] with [isOurs] set. Nested calls keep the outer flag. */
    fun <T> whileLinking(action: () -> T): T {
        val previous = active.get()
        active.set(true)
        try {
            return action()
        } finally {
            active.set(previous)
        }
    }
}
