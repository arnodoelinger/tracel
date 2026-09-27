package com.tracel.plugin.rollback.trace

/**
 * Diagnostic.
 *
 * Phase timings of one rollback.
 */
interface RollbackTrace {
    companion object {
        /** Measures nothing. What undo and every untraced call get. */
        val NONE: RollbackTrace = NoTrace
    }

    /** Times [block] under [name]. Repeated names accumulate. */
    suspend fun <T> span(name: String, block: suspend () -> T): T

    /** Same as [span] for a region-thread callback that is not a coroutine. */
    fun <T> measure(name: String, block: () -> T): T

    /** Add [nanos]. */
    fun addNanos(name: String, nanos: Long)

    /** Records a plain number next to the timings — how many blocks, how many steps. */
    fun note(name: String, value: Int)

    /** Keeps the largest value seen under [name]. */
    fun noteMax(name: String, value: Int)

    /** Keeps the smallest value seen under [name]. */
    fun noteMin(name: String, value: Int)

    /** Adds to a running total under [name]. */
    fun add(name: String, value: Int)

    /** Folia hop wait: start before dispatch, invoke first thing on the region thread. */
    fun stopwatch(name: String): () -> Unit

    /** The whole thing, longest phase first, as chat lines. */
    fun render(): List<String>
}
