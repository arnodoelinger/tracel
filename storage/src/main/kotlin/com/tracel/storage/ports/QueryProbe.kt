package com.tracel.storage.ports

import java.util.concurrent.atomic.LongAdder

/** What a query actually touched, next to what it returned. */
object QueryProbe {
    private val indexRowsAdder = LongAdder()
    private val keptAdder = LongAdder()
    private val recordsAdder = LongAdder()
    private val cursorsAdder = LongAdder()

    fun walked(rows: Long, kept: Long) {
        indexRowsAdder.add(rows)
        keptAdder.add(kept)
        cursorsAdder.increment()
    }

    fun opened(records: Long) {
        recordsAdder.add(records)
    }

    fun cursors(count: Long) {
        cursorsAdder.add(count)
    }

    private val phases = java.util.concurrent.ConcurrentHashMap<String, LongAdder>()

    inline fun <T> phase(name: String, block: () -> T): T {
        val at = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, System.nanoTime() - at)
        }
    }

    fun record(name: String, nanos: Long) {
        phases.computeIfAbsent(name) { LongAdder() }.add(nanos)
    }

    fun reset() {
        indexRowsAdder.reset()
        keptAdder.reset()
        recordsAdder.reset()
        cursorsAdder.reset()
        phases.clear()
    }

    fun read(): Reading = Reading(
        indexRowsAdder.sum(),
        keptAdder.sum(),
        recordsAdder.sum(),
        cursorsAdder.sum(),
        phases.mapValues { it.value.sum() / 1_000_000 },
    )

    data class Reading(
        val indexRows: Long,
        val kept: Long,
        val records: Long,
        val cursors: Long,
        val millis: Map<String, Long> = emptyMap(),
    )
}
