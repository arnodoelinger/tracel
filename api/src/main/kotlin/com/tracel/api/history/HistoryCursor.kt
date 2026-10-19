package com.tracel.api.history

import com.tracel.api.Unstable

/**
 * A spot in history, newest first: when, then the log's own sequence for what
 * happened in the same millisecond.
 *
 * Paging by it never skips or repeats a record, however much is written meanwhile,
 * which an offset cannot promise.
 */
@Unstable
public data class HistoryCursor(public val millis: Long, public val seq: Long) : Comparable<HistoryCursor> {
    override fun compareTo(other: HistoryCursor): Int =
        compareValuesBy(this, other, HistoryCursor::millis, HistoryCursor::seq)
}
