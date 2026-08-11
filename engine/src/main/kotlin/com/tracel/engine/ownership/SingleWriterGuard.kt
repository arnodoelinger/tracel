package com.tracel.engine.ownership

import java.util.concurrent.atomic.AtomicReference

/**
 * Confines writes to whichever thread claims ownership first — the same
 * contract `Bukkit`'s main thread gives every plugin for free, made explicit
 * here since the engine itself has no scheduler to lean on.
 *
 * A second thread reaching a write path is not a race to tolerate or
 * synchronize away: it means something scheduled engine work off the main
 * thread, which is a bug in the caller. [checkIn] fails loudly instead of
 * letting two threads interleave mutations of the same in-memory state.
 */
public class SingleWriterGuard {
    /** The thread that first called [checkIn] and thus owns the right to write. */
    private val owner = AtomicReference<Thread?>(null)

    /** Checks that the current thread is the one that owns the right to write, or claims it if no thread has yet. */
    public fun checkIn() {
        val current = Thread.currentThread()
        val previous = owner.compareAndExchange(null, current)
        check(previous == null || previous === current) {
            "single-writer violation: owned by ${previous?.name}, entered from ${current.name}"
        }
    }
}
