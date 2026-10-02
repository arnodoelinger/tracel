package com.tracel.plugin.mode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Runs writes in the order they were asked for: a visit must be opened before it is closed. */
internal class ActorWrites(scope: CoroutineScope) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (write in queue) runCatching { write() } }
    }

    fun submit(write: suspend () -> Unit) {
        queue.trySend(write)
    }
}
