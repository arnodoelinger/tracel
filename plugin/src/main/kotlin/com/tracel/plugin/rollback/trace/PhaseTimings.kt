package com.tracel.plugin.rollback.trace

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

// TODO: rewrite

/** Histogram diagnostics. */
class PhaseTimings : RollbackTrace {
    private val spans = ConcurrentHashMap<String, Long>()
    private val counts = ConcurrentHashMap<String, Int>()
    private val notes = ConcurrentHashMap<String, Int>()
    private val started = TimeSource.Monotonic.markNow()

    private companion object {
        const val BAR_WIDTH = 30
        const val DETAIL_PARENTS = 6
        const val SEP = " / "
    }

    override suspend fun <T> span(name: String, block: suspend () -> T): T {
        val at = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            addNanos(name, at.elapsedNow().inWholeNanoseconds)
        }
    }

    override fun <T> measure(name: String, block: () -> T): T {
        val at = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            addNanos(name, at.elapsedNow().inWholeNanoseconds)
        }
    }

    override fun addNanos(name: String, nanos: Long) {
        if (nanos <= 0L) return
        spans.merge(name, nanos, Long::plus)
        counts.merge(name, 1, Int::plus)
    }

    override fun note(name: String, value: Int) {
        notes[name] = value
    }

    override fun noteMax(name: String, value: Int) {
        notes.merge(name, value, ::maxOf)
    }

    override fun noteMin(name: String, value: Int) {
        notes.merge(name, value, ::minOf)
    }

    override fun add(name: String, value: Int) {
        if (value != 0) notes.merge(name, value, Int::plus)
    }

    override fun stopwatch(name: String): () -> Unit {
        val at = TimeSource.Monotonic.markNow()
        return {
            val nanos = at.elapsedNow().inWholeNanoseconds
            noteMin("$name ms", (nanos / 1_000_000L).toInt())
            spans.putIfAbsent(name, nanos)
        }
    }

    override fun render(): List<String> {
        val total = started.elapsedNow()
        val top = spans.entries.filter { SEP !in it.key }.sortedByDescending { it.value }
        val measured = top.sumOf { it.value }.nanoseconds
        val slowest = top.maxOfOrNull { it.value } ?: 1L

        val out = mutableListOf("Rollback took ${total.readable()} — where it went:")
        val detail = top.take(DETAIL_PARENTS).map { it.key }.toSet()
        val byParent = spans.entries.filter { SEP in it.key }.groupBy { it.key.substringBefore(SEP) }
        for ((name, nanos) in top) {
            out += line("  ", name, nanos, total.inWholeNanoseconds, slowest, counts[name])
            if (name !in detail) continue
            val kids = byParent[name].orEmpty().sortedByDescending { it.value }
            val kidSlowest = kids.maxOfOrNull { it.value } ?: 1L
            for ((child, childNanos) in kids) {
                val short = child.substringAfter(SEP)
                out += line("    ", short, childNanos, nanos, kidSlowest, counts[child])
            }
        }

        val unaccounted = total - measured
        if (unaccounted.inWholeMilliseconds > 1) {
            out += "  %-22s %8s %5.1f%%".format(
                Locale.ROOT,
                "(elsewhere)",
                unaccounted.readable(),
                unaccounted.inWholeNanoseconds * 100.0 / total.inWholeNanoseconds,
            )
        } else if ((-unaccounted).inWholeMilliseconds > 1) {
            out += "  %-22s %8s  saved by running phases at the same time".format(
                Locale.ROOT,
                "(overlap)",
                (-unaccounted).readable(),
            )
        }
        if (notes.isNotEmpty()) out += "  " + notes.entries.joinToString(", ") { "${it.key}=${it.value}" }
        return out
    }

    private fun line(
        indent: String,
        name: String,
        nanos: Long,
        ofNanos: Long,
        slowest: Long,
        count: Int?,
    ): String {
        val share = if (ofNanos > 0) nanos * 100.0 / ofNanos else 0.0
        val bar = "|".repeat((nanos * BAR_WIDTH / slowest).toInt().coerceAtLeast(if (nanos > 0) 1 else 0))
        val times = count?.takeIf { it > 1 }?.let { " x$it" } ?: ""
        val width = if (indent.length > 2) 20 else 22
        return "%s%-${width}s %8s %5.1f%% %s%s".format(
            Locale.ROOT, indent, name, nanos.nanoseconds.readable(), share, bar, times,
        )
    }

    private fun Duration.readable(): String = when {
        inWholeMilliseconds >= 1000 -> "%.2fs".format(Locale.ROOT, inWholeNanoseconds / 1_000_000_000.0)
        inWholeMicroseconds >= 1000 -> "%.1fms".format(Locale.ROOT, inWholeNanoseconds / 1_000_000.0)
        else -> "%.0fus".format(Locale.ROOT, inWholeNanoseconds / 1_000.0)
    }
}
