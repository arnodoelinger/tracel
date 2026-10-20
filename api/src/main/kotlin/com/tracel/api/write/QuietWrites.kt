package com.tracel.api.write

import com.tracel.api.Unstable
import com.tracel.api.result.Res

/** Changing the world without history, for a plugin granted `quiet`. */
@Unstable
public interface QuietWrites {
    /**
     * Runs [action] on this thread with logging off for what it does to the world. It must not suspend or hand work
     * to another thread: only what happens inside, right here, goes unlogged.
     *
     * History gets a hole there. A later rollback will not undo what was done, nor know it was.
     */
    public fun <T> quietly(action: () -> T): Res<T, WriteError>
}
