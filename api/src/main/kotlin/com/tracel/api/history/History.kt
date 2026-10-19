package com.tracel.api.history

import com.tracel.api.Unstable
import com.tracel.api.result.Res
import kotlinx.coroutines.flow.Flow

/**
 * The answer to one [HistoryQuery], read lazily, newest first.
 *
 * Nothing is read until [records] is collected or [page] is called, and each
 * read sees history as it is then.
 */
@Unstable
public interface History {
    /** What this history reads. */
    public val query: HistoryQuery

    /**
     * Every record, read a page at a time as it is collected.
     *
     * A read that fails ends the flow with an exception carrying the [HistoryReadError];
     * use [page] to get it as a [Res] instead.
     */
    public val records: Flow<HistoryRecord>

    /**
     * At most about [size] records older than [from], or the newest when [from] is `null`.
     *
     * The flows of one transaction are never split, so a page can run a little past [size].
     */
    public suspend fun page(from: HistoryCursor? = null, size: Int = DEFAULT_PAGE): Res<HistoryPage, HistoryReadError>

    public companion object {
        public const val DEFAULT_PAGE: Int = 100
    }
}

/** One page of a [History]: the [records], and where the next page starts, `null` after the last. */
@Unstable
public data class HistoryPage(public val records: List<HistoryRecord>, public val next: HistoryCursor?)
