package com.tracel.plugin.api.read

import com.tracel.api.history.HistoryCursor

/**
 * One log read backwards from a [HistoryCursor].
 *
 * [fetch] answers newest first, at most `limit` rows, none newer than `until`, and [until] caps what it is asked;
 * the log's order inside one millisecond is not trusted.
 */
internal class LogSource<T>(
    private val fetch: suspend (until: Long?, limit: Int) -> List<T>,
    val cursorOf: (T) -> HistoryCursor,
    private val until: Long? = null,
) {
    /**
     * At least [size] rows older than [from], newest first, or all that are left.
     *
     * A full window can cut its oldest millisecond anywhere, so rows of that millisecond
     * only count once the window held more than it.
     */
    suspend fun older(from: HistoryCursor?, size: Int): List<T> {
        val bound = listOfNotNull(from?.millis, until).minOrNull()
        var window = size + 1
        while (true) {
            val got = fetch(bound, window)
            val older = if (from == null) got else got.filter { cursorOf(it) < from }
            if (got.size < window || window >= WIDEST) return older.sortedByDescending(cursorOf)
            val edge = got.minOf { cursorOf(it).millis }
            val sure = older.filter { cursorOf(it).millis > edge }
            if (sure.size >= size) return sure.sortedByDescending(cursorOf)
            window = minOf(WIDEST, window * 4)
        }
    }

    companion object {
        const val WIDEST: Int = 1 shl 20
    }
}

/**
 * Several logs read as one history, newest first by (millis, seq).
 *
 * Each log gives its own `size` newest rows past the cursor;
 * the newest `size` of them all are the page, and the last of those is where the next one starts.
 */
internal object CursorPager {
    class Rows<T>(val rows: List<T>, val next: HistoryCursor?)

    suspend fun <T> page(sources: List<LogSource<T>>, from: HistoryCursor?, size: Int): Rows<T> {
        val all = ArrayList<Pair<HistoryCursor, T>>()
        for (source in sources) for (row in source.older(from, size)) all += source.cursorOf(row) to row
        all.sortByDescending { it.first }
        if (all.size < size) return Rows(all.map { it.second }, null)
        val taken = all.subList(0, size)
        return Rows(taken.map { it.second }, taken.last().first)
    }
}
