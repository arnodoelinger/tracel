package com.tracel.plugin.status.rate

/** Counts how fast the store takes writes: samples of a running total, read as a rate over the last minute. */
class WriteRate(
    private val total: () -> Long,
    private val keep: Int = 13,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Notes the running total now. */
    @Synchronized
    fun sample() {
        val now = clock()
        val count = runCatching(total).getOrNull() ?: return
        samples.addLast(now to count)
        while (samples.size > keep) samples.removeFirst()
    }

    /** Writes per second between the oldest and newest sample, or `null` before there are two. */
    @Synchronized
    fun perSecond(): Double? {
        if (samples.size < 2) return null
        val (from, first) = samples.first()
        val (to, last) = samples.last()
        if (to <= from) return null
        return (last - first) * 1000.0 / (to - from)
    }
}
