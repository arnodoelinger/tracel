package com.tracel.plugin.importer.coreprotect

/**
 * Every table of a `CoreProtect` database as one run of rows, oldest first.
 *
 * Each table is read in the order it was written, and the row taken next is the oldest of what the tables have
 * at their heads. `CoreProtect` leans on that itself: what a sign said is written five seconds into the past, so
 * that it is read before the row that says the sign was broken.
 *
 * @param after the last row already taken from each table, in [SourceTable] order
 * @param upTo the row each table is read up to, so a database still being written to has an end
 */
class CoreProtectStream(
    private val database: CoreProtectDatabase,
    after: List<Long>,
    private val upTo: List<Long>,
) {
    private val heads = SourceTable.entries.map { ArrayDeque<SourceRow>() }
    private val fetched = LongArray(SourceTable.entries.size) { after.getOrElse(it) { 0L } }
    private val taken = LongArray(SourceTable.entries.size) { after.getOrElse(it) { 0L } }

    private companion object {
        const val FETCH_ROWS = 2_000
    }

    /** The last row taken from each table so far. */
    val marks: List<Long> get() = taken.toList()

    /** How many rows are left, counting by row number: a table with holes in it says more than it has. */
    val left: Long get() = taken.indices.sumOf { (upTo[it] - taken[it]).coerceAtLeast(0) }

    /** The next rows, at most [limit] of them, or none when every table is read. */
    fun next(limit: Int): List<SourceRow> {
        val out = ArrayList<SourceRow>(limit)
        while (out.size < limit) {
            var oldest = -1
            for (i in heads.indices) {
                val head = head(i) ?: continue
                if (oldest < 0 || head.time < heads[oldest].first().time) oldest = i
            }
            if (oldest < 0) break
            val row = heads[oldest].removeFirst()
            taken[oldest] = row.rowId
            out += row
        }
        // A table read to its end has nothing after the last row it gave, whatever number that was
        for (i in heads.indices) if (heads[i].isEmpty() && fetched[i] >= upTo[i]) taken[i] = maxOf(taken[i], upTo[i])
        return out
    }

    private fun head(index: Int): SourceRow? {
        val buffer = heads[index]
        if (buffer.isEmpty() && fetched[index] < upTo[index]) {
            val rows = database.rows(SourceTable.entries[index], fetched[index], upTo[index], FETCH_ROWS)
            buffer += rows
            fetched[index] = if (rows.size < FETCH_ROWS) upTo[index] else rows.last().rowId
        }
        return buffer.firstOrNull()
    }
}
