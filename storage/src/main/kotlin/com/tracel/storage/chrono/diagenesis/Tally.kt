package com.tracel.storage.chrono.diagenesis

import java.util.concurrent.atomic.AtomicLong

/** What the collector has done since it started, by the generation it made. */
internal class Tally {
    private val bytes = Array(Generation.entries.size) { AtomicLong() }
    private val runs = Array(Generation.entries.size) { AtomicLong() }

    fun wrote(generation: Generation, written: Long) {
        bytes[generation.ordinal].addAndGet(written)
        runs[generation.ordinal].incrementAndGet()
    }

    fun bytes(generation: Generation): Long = bytes[generation.ordinal].get()

    fun runs(generation: Generation): Long = runs[generation.ordinal].get()
}

/** What the collector has done: merges and bytes written, in all and per generation made. */
data class DiagenesisStats(
    val waitingFragments: Int,
    val newBytes: Long,
    val siltRuns: Long,
    val siltBytes: Long,
    val stratumRuns: Long,
    val stratumBytes: Long,
    val glacierRuns: Long,
    val glacierBytes: Long,
    val permafrostRuns: Long,
    val permafrostBytes: Long,
) {
    val runs: Long get() = siltRuns + stratumRuns + glacierRuns + permafrostRuns
    val bytes: Long get() = siltBytes + stratumBytes + glacierBytes + permafrostBytes
}
