package com.tracel.plugin.metrics

import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.scope.LookupScope
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.status.rollback.RollbackSize
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Counts what happened since the last report: which commands and flags get used, how rollbacks go, what breaks.
 *
 * Buckets and names only.
 */
internal object Telemetry {
    const val COMMANDS = "commands"
    const val LOOKUP_FLAGS = "lookup_flags"
    const val ROLLBACK_FLAGS = "rollback_flags"
    const val ROLLBACK_RADIUS = "rollback_radius"
    const val ROLLBACK_WINDOW = "rollback_window"
    const val ROLLBACK_OUTCOMES = "rollback_outcomes"
    const val ROLLBACK_SIZES = "rollback_sizes"
    const val ROLLBACK_TIME = "rollback_time"
    const val ROLLBACK_SPEED = "rollback_speed"
    const val UNDO_OUTCOMES = "undo_outcomes"
    const val ERRORS = "errors"
    const val WARNINGS = "warnings"

    const val MAX_KEYS = 64
    const val OTHER = "other"

    private const val OWN_PACKAGE = "com.tracel."
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    private val charts = ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>>()
    private val peakBacklog = AtomicLong()

    /** One more of [key] on [chart]. Any thread. */
    fun count(chart: String, key: String) {
        val keys = charts.computeIfAbsent(chart) { ConcurrentHashMap() }
        val adder = keys[key] ?: keys.computeIfAbsent(if (keys.size >= MAX_KEYS) OTHER else key) { LongAdder() }
        adder.increment()
    }

    /** What [chart] counted since it was last asked, and back to zero. */
    fun drain(chart: String): Map<String, Int> {
        val keys = charts[chart] ?: return emptyMap()
        val out = HashMap<String, Int>()
        for ((key, adder) in keys) {
            val n = adder.sumThenReset()
            if (n > 0) out[key] = n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return out
    }

    /** A command that ran, by its literal path: the arguments are not in it. */
    fun command(path: String) = count(COMMANDS, path)

    /** Which flags a lookup or a rollback was asked with: the ones nobody uses are the ones to cut. */
    fun flags(chart: String, parsed: ParsedLookupArgs) {
        var any = false
        fun used(flag: String, set: Boolean) {
            if (!set) return
            any = true
            count(chart, flag)
        }
        used("user", parsed.users.isNotEmpty())
        used("item", parsed.item != null)
        used("action", parsed.actions.isNotEmpty())
        used("time", parsed.since != null)
        used("until", parsed.until != null)
        used("world", parsed.world != null)
        used("horizontal", parsed.horizontalOnly)
        used("preview", parsed.preview)
        used("blocks_only", parsed.structureOnly)
        used("items_only", parsed.materialOnly)
        used("strict", parsed.strict)
        used("confirm", parsed.confirmed)
        used("natural", parsed.natural)
        used("all", parsed.all)
        used("each", parsed.each)
        when (parsed.scope) {
            is LookupScope.Blocks -> used("radius", true)
            is LookupScope.Chunks -> used("chunk_radius", true)
            LookupScope.CurrentChunk -> used("this_chunk", true)
            LookupScope.CurrentBlock -> used("this_block", true)
            null -> Unit
        }
        if (!any) count(chart, "none")
    }

    /** How wide and how far back a rollback reaches: what `max-radius` and the purge defaults should be. */
    fun rollbackReach(parsed: ParsedLookupArgs, nowMillis: Long = System.currentTimeMillis()) {
        count(
            ROLLBACK_RADIUS,
            when (val scope = parsed.scope) {
                is LookupScope.Blocks -> radiusBucket(scope.radius)
                is LookupScope.Chunks -> radiusBucket(scope.radius.coerceAtMost(Int.MAX_VALUE shr 4) shl 4)
                LookupScope.CurrentChunk -> "chunk"
                LookupScope.CurrentBlock -> "block"
                null -> if (parsed.world != null) "world" else "everywhere"
            },
        )
        count(ROLLBACK_WINDOW, parsed.since?.let { windowBucket(nowMillis - it) } ?: "all time")
    }

    /** How a rollback ended. */
    fun rollback(outcome: String) = count(ROLLBACK_OUTCOMES, outcome)

    /** A rollback that went through: clean or partial, how big, how long, how fast. */
    fun rollbackDone(done: RollbackResult.Done, tookMillis: Long) {
        val clean = done.structure.fullyRestored && done.material.fullyRestored
        rollback(if (clean) "clean" else "partial")
        val records = done.plan.taken.size.toLong()
        count(ROLLBACK_SIZES, RollbackSize.of(records).key)
        count(ROLLBACK_TIME, tookBucket(tookMillis))
        if (records > 0 && tookMillis >= 100) count(ROLLBACK_SPEED, speedBucket(records * 1000 / tookMillis))
    }

    /** How an undo ended. */
    fun undo(outcome: String) = count(UNDO_OUTCOMES, outcome)

    /** The exception and the first `Tracel` class on its stack: enough to find the bug, nothing about the server. */
    fun error(failure: Throwable) {
        val name = failure::class.java.simpleName.ifEmpty { "Anonymous" }
        val frame = failure.stackTrace.firstOrNull { it.className.startsWith(OWN_PACKAGE) }
        val where = frame?.className?.substringAfterLast('.')?.substringBefore('$')?.removeSuffix("Kt")
        count(ERRORS, if (where.isNullOrEmpty()) name else "$name in $where")
    }

    /** A warning's kind: its key up to the first colon, the rest names an entity or a block. */
    fun warning(key: String) = count(WARNINGS, key.substringBefore(':'))

    /** The capture ring's backlog right now. Only the highest one since the last report is kept. */
    fun backlog(now: Long) {
        peakBacklog.accumulateAndGet(now, ::maxOf)
    }

    /** The highest backlog seen since it was last asked, and back to zero. */
    fun drainPeakBacklog(): Long = peakBacklog.getAndSet(0)

    internal fun radiusBucket(blocks: Int): String = when {
        blocks <= 8 -> "1-8"
        blocks <= 32 -> "9-32"
        blocks <= 128 -> "33-128"
        blocks <= 256 -> "129-256"
        else -> "257+"
    }

    internal fun windowBucket(millis: Long): String = when {
        millis <= HOUR -> "1h"
        millis <= DAY -> "1d"
        millis <= 7 * DAY -> "7d"
        millis <= 30 * DAY -> "30d"
        millis <= 90 * DAY -> "90d"
        else -> "90d+"
    }

    internal fun tookBucket(millis: Long): String = when {
        millis < 1_000 -> "under 1s"
        millis < 5_000 -> "1-5s"
        millis < 30_000 -> "5-30s"
        millis < 120_000 -> "30s-2m"
        else -> "2m+"
    }

    internal fun speedBucket(perSecond: Long): String = when {
        perSecond < 1_000 -> "under 1k/s"
        perSecond < 10_000 -> "1k-10k/s"
        perSecond < 100_000 -> "10k-100k/s"
        else -> "100k+/s"
    }
}
