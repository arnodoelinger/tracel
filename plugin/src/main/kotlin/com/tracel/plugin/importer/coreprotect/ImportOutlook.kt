package com.tracel.plugin.importer.coreprotect

import com.tracel.engine.foreign.ImportMark
import com.tracel.plugin.importer.coreprotect.source.SourceTable

/**
 * What an import is about to do, or did.
 *
 * @property resumed where an earlier import of the same database stopped, if there was one
 * @property lastRows the row each table ended on when the import looked, in [SourceTable] order
 * @property ownSince when our own history starts, or `null` while there is none
 */
class ImportOutlook(
    val version: String?,
    val resumed: ImportMark?,
    val lastRows: List<Long>,
    val span: Pair<Long, Long>?,
    val ownSince: Long?,
) {
    /** The rows of each table still to read, counted by row number. */
    val left: List<Long> =
        lastRows.mapIndexed { i, last -> (last - (resumed?.rows?.getOrNull(i) ?: 0L)).coerceAtLeast(0) }
}
