package com.tracel.plugin.actor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Runs writes in the order they were asked for: a visit must be opened before it is closed. */
internal class ActorWrites(scope: CoroutineScope) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (write in queue) runCatching { write() } }
    }

    /**
     * Submit a write to the actor.
     *
     * The write will be executed in the order it was submitted.
     */
    fun submit(write: suspend () -> Unit) {
        queue.trySend(write)
    }
}
