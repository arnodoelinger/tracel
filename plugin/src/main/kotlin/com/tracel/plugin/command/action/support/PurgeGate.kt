package com.tracel.plugin.command.action.support

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/** Keeps a purge and a rollback off each other's data. */
class PurgeGate(private val rollbackRunning: () -> Boolean) {
    private val slicing = AtomicBoolean(false)

    private companion object {
        const val POLL_MILLIS = 500L
        const val SLICE_POLL_MILLIS = 10L
    }

    /** Waits until no rollback is running, calling [onWait] once if it has to wait at all. */
    suspend fun awaitIdle(onWait: () -> Unit = {}) {
        if (!rollbackRunning()) return
        onWait()
        while (rollbackRunning()) delay(POLL_MILLIS.milliseconds)
    }

    /** Runs [slice] once no rollback is running, and keeps one from starting before it is done. */
    suspend fun slice(onWait: () -> Unit = {}, slice: suspend () -> Unit) {
        var told = false
        while (true) {
            awaitIdle { if (!told) onWait().also { told = true } }
            slicing.set(true)
            if (rollbackRunning()) {
                slicing.set(false)
                continue
            }
            try {
                slice()
            } finally {
                slicing.set(false)
            }
            return
        }
    }

    /** For a rollback that has just taken its gate: waits out the slice that was already running. */
    suspend fun awaitSlice() {
        while (slicing.get()) delay(SLICE_POLL_MILLIS.milliseconds)
    }
}
