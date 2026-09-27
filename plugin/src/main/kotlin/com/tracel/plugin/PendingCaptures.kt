package com.tracel.plugin

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tracks captures accepted but not yet written.
 *
 * Counted per generation: [await] waits for what was owed before it was called, never for the
 * steady stream owed after. A single counter needed a moment of total silence, and a busy server
 * has none, so every rollback sat out the whole timeout.
 */
class PendingCaptures {
    private val generation = AtomicLong()
    private val outstanding = ConcurrentHashMap<Long, AtomicInteger>()

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
        const val POLL_MS = 1L
    }

    /** Marks a capture as pending. Hand the ticket back to [done]. */
    fun owed(): Long {
        val ticket = generation.get()
        outstanding.computeIfAbsent(ticket) { AtomicInteger() }.incrementAndGet()
        return ticket
    }

    /** Marks the capture [ticket] belongs to as written. */
    fun done(ticket: Long) {
        outstanding.computeIfAbsent(ticket) { AtomicInteger() }.decrementAndGet()
    }

    /**
     * Waits for every capture owed before this call, up to [timeoutMs].
     *
     * @return `true` if all of them were written.
     */
    suspend fun await(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val upTo = generation.getAndIncrement()
        outstanding.entries.removeIf { it.key < upTo - 1 && it.value.get() <= 0 }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (owedUpTo(upTo) && System.currentTimeMillis() < deadline) {
            delay(POLL_MS.milliseconds)
        }
        return !owedUpTo(upTo)
    }

    private fun owedUpTo(upTo: Long): Boolean = outstanding.any { (ticket, count) -> ticket <= upTo && count.get() > 0 }
}
