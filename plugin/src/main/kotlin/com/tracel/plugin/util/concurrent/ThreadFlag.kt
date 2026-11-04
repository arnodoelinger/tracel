package com.tracel.plugin.util.concurrent

/**
 * A flag that is set for the thread running a block, and for no other.
 *
 * Nested blocks keep the outer one set.
 *
 * `Folia`: a region is one thread at a time, and the events a block fires run inside it.
 */
internal class ThreadFlag {
    private val state = ThreadLocal.withInitial { false }

    /** Whether the calling thread is inside [during], or between [enter] and [leave]. */
    val isSet: Boolean get() = state.get()

    /** Runs [action] with the flag set, and puts it back as it was afterwards. */
    fun <T> during(action: () -> T): T {
        val previous = enter()
        try {
            return action()
        } finally {
            leave(previous)
        }
    }

    /** Sets the flag. Pass what this returns to [leave]. */
    fun enter(): Boolean {
        val previous = state.get()
        state.set(true)
        return previous
    }

    /** Puts the flag back to what [enter] returned. */
    fun leave(previous: Boolean) {
        state.set(previous)
    }
}
