package com.tracel.storage.history

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/** What a query actually touched, next to what it returned. */
object QueryProbe {
    private val indexRowsAdder = LongAdder()
    private val keptAdder = LongAdder()
    private val recordsAdder = LongAdder()
    private val cursorsAdder = LongAdder()
    private val scannedAdder = LongAdder()
    private val pointGotAdder = LongAdder()
    private val inlinedAdder = LongAdder()
    private val deltaRowsAdder = LongAdder()
    private val deltaBlocksAdder = LongAdder()

    fun opened(records: Long) {
        recordsAdder.add(records)
    }

    fun cursors(count: Long) {
        cursorsAdder.add(count)
    }

    fun scanned(count: Long) {
        scannedAdder.add(count)
    }

    fun pointGot(count: Long) {
        pointGotAdder.add(count)
    }

    private val phases = ConcurrentHashMap<String, LongAdder>()

    fun record(name: String, nanos: Long) {
        phases.computeIfAbsent(name) { LongAdder() }.add(nanos)
    }

    fun reset() {
        indexRowsAdder.reset()
        keptAdder.reset()
        recordsAdder.reset()
        cursorsAdder.reset()
        scannedAdder.reset()
        pointGotAdder.reset()
        inlinedAdder.reset()
        deltaRowsAdder.reset()
        deltaBlocksAdder.reset()
        phases.clear()
    }

    fun read(): Reading = Reading(
        indexRowsAdder.sum(),
        keptAdder.sum(),
        recordsAdder.sum(),
        cursorsAdder.sum(),
        scannedAdder.sum(),
        pointGotAdder.sum(),
        inlinedAdder.sum(),
        deltaRowsAdder.sum(),
        deltaBlocksAdder.sum(),
        phases.mapValues { it.value.sum() / 1_000_000 },
        phases.mapValues { it.value.sum() },
    )

    data class Reading(
        val indexRows: Long,
        val kept: Long,
        val records: Long,
        val cursors: Long,
        val scanned: Long,
        val pointGot: Long,
        val inlined: Long,
        val deltaRows: Long,
        val deltaBlocks: Long,
        val millis: Map<String, Long> = emptyMap(),
        val nanos: Map<String, Long> = emptyMap(),
    )
}
