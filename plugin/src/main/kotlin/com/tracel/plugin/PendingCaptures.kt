package com.tracel.plugin

import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Tracks captures accepted but not yet written.
 *
 * We don't know when the write will finish. Keep a count and wait for it to reach zero.
 */
class PendingCaptures {
    private val outstanding = AtomicInteger()

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
        const val POLL_MS = 1L
    }

    /** Marks a capture as pending. */
    fun owed() {
        outstanding.incrementAndGet()
    }

    /** Marks a capture as written. */
    fun done() {
        outstanding.decrementAndGet()
    }

    /**
     * Waits for all pending captures, up to [timeoutMs].
     *
     * @return `true` if all captures were written.
     */
    suspend fun await(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (outstanding.get() <= 0) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (outstanding.get() > 0 && System.currentTimeMillis() < deadline) {
            delay(POLL_MS.milliseconds)
        }
        return outstanding.get() <= 0
    }
}
