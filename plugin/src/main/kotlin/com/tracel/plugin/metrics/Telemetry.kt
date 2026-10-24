package com.tracel.plugin.metrics

import com.tracel.model.holder.HolderId
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.scope.LookupScope
import com.tracel.api.rollback.RollbackApplied
import com.tracel.plugin.rollback.result.report.SkippedStep
import com.tracel.plugin.status.rollback.RollbackSize
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Counts what happened since the last report: which commands and flags get used, how rollbacks go, what breaks.
 *
 * Buckets and names only.
 */
internal object Telemetry {
    private const val DRILL = '\u001f'

    private const val PARTIAL_PER_ROLLBACK = 12
    private const val REASON_CHARS = 70
    private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val NAMESPACED = Regex("\\b[a-z0-9_.-]+:[a-z0-9_./-]+")
    private val MATERIAL = Regex("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b|\\b[A-Z]{4,}\\b")
    private val DIGITS = Regex("-?\\d+")
    private val SPACES = Regex("\\s+")

    private const val OWN_PACKAGE = "com.tracel."
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    private val charts = ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>>()
    private val peakBacklog = AtomicLong()
    private val rollbacksDone = LongAdder()

    const val COMMANDS = "commands"
    const val LOOKUP_FLAGS = "lookup_flags"
    const val ROLLBACK_FLAGS = "rollback_flags"
    const val ROLLBACK_RADIUS = "rollback_radius"
    const val ROLLBACK_WINDOW = "rollback_window"
    const val ROLLBACK_OUTCOMES = "rollback_outcomes"
    const val ROLLBACK_SIZES = "rollback_sizes"
    const val ROLLBACK_TIME = "rollback_time"
    const val ROLLBACK_SPEED = "rollback_speed"
    const val ROLLBACK_PARTIAL = "rollback_partial"
    const val UNDO_OUTCOMES = "undo_outcomes"
    const val ERRORS = "errors"
    const val WARNINGS = "warnings"

    const val MAX_KEYS = 64
    const val OTHER = "other"

    /** One more of [key] on [chart]. Any thread. */
    fun count(chart: String, key: String) {
        val keys = charts.computeIfAbsent(chart) { ConcurrentHashMap() }
        val adder = keys[key] ?: keys.computeIfAbsent(if (keys.size >= MAX_KEYS) OTHER else key) { LongAdder() }
        adder.increment()
    }

    /** One more of [inner] inside the slice [outer] of a drilldown [chart]. */
    fun countInside(chart: String, outer: String, inner: String) = count(chart, outer + DRILL + inner)

    /** [drain] of a drilldown chart: its slices, each with the exact values counted inside. */
    fun drainInside(chart: String): Map<String, Map<String, Int>> {
        val out = HashMap<String, HashMap<String, Int>>()
        for ((key, n) in drain(chart)) {
            val outer = key.substringBefore(DRILL)
            val inner = if (DRILL in key) key.substringAfter(DRILL) else key
            out.getOrPut(outer) { HashMap() }.merge(inner, n, Int::plus)
        }
        return out
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
    fun rollbackDone(done: RollbackApplied, tookMillis: Long) {
        rollback(finish(done.skipped, done.itemsRestored))
        val records = done.records.toLong()
        count(ROLLBACK_SIZES, RollbackSize.of(records).key)
        rollbacksDone.increment()
        countInside(ROLLBACK_TIME, tookBucket(tookMillis), tookExact(tookMillis))
        if (records > 0 && tookMillis >= 100) count(ROLLBACK_SPEED, speedBucket(records * 1000 / tookMillis))
    }

    /** What a rollback left undone. */
    fun leftUndone(skipped: List<SkippedStep>, failures: Map<HolderId, String>) {
        val seen = HashSet<String>()
        fun note(group: String, reason: String) {
            for (part in reason.split(';')) {
                val key = reasonKey(part)
                if (key.isNotEmpty() && seen.add(group + DRILL + key) && seen.size <= PARTIAL_PER_ROLLBACK) {
                    countInside(ROLLBACK_PARTIAL, group, key)
                }
            }
        }
        for (step in skipped) note("blocks skipped", step.reason)
        for ((holder, reason) in failures) note("items: " + (holder::class.simpleName ?: "holder"), reason)
    }

    /** A reason with what it names taken out: IDs, counts, materials. */
    internal fun reasonKey(reason: String): String = reason
        .replace(UUID_TEXT, "")
        .replace(NAMESPACED, "<id>")
        .replace(MATERIAL, "<item>")
        .replace(DIGITS, "#")
        .replace(SPACES, " ")
        .trim()
        .take(REASON_CHARS)

    /** Clean, or partial and what was left undone: blocks that were skipped, items that stayed put, or both. */
    fun finish(skipped: Int, itemsRestored: Boolean): String = when {
        skipped == 0 && itemsRestored -> "clean"
        itemsRestored -> "partial: blocks skipped"
        skipped == 0 -> "partial: items not restored"
        else -> "partial: blocks and items"
    }

    /** Rollbacks that went through since this was last asked, and back to zero. */
    fun drainRollbacksDone(): Long = rollbacksDone.sumThenReset()

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
        millis < 300 -> "instantly"
        millis < 1_000 -> "0.3-1s"
        millis < 2_000 -> "1-2s"
        millis < 3_000 -> "2-3s"
        millis < 5_000 -> "3-5s"
        millis < 10_000 -> "5-10s"
        millis < 15_000 -> "10-15s"
        millis < 30_000 -> "15-30s"
        millis < 60_000 -> "30-60s"
        millis < 120_000 -> "1-2m"
        millis < 300_000 -> "2-5m"
        else -> "5m+"
    }

    internal fun tookExact(millis: Long): String = when {
        millis < 300 -> "%d ms".format(Locale.ROOT, millis / 100 * 100)
        millis < 10_000 -> "%.1fs".format(Locale.ROOT, millis / 500 * 0.5)
        millis < 60_000 -> "${millis / 5_000 * 5}s"
        millis < 600_000 -> "${millis / 60_000}m${(millis % 60_000) / 30_000 * 30}s"
        else -> "10m+"
    }

    internal fun speedBucket(perSecond: Long): String = when {
        perSecond < 1_000 -> "under 1k/s"
        perSecond < 10_000 -> "1k-10k/s"
        perSecond < 100_000 -> "10k-100k/s"
        else -> "100k+/s"
    }
}
