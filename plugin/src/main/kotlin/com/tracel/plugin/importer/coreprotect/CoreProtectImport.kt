package com.tracel.plugin.importer.coreprotect

import com.tracel.engine.foreign.ForeignHistory
import com.tracel.plugin.importer.coreprotect.source.CoreProtectDatabase
import com.tracel.plugin.importer.coreprotect.source.CoreProtectStream
import com.tracel.plugin.importer.coreprotect.translate.CoreProtectTranslator
import com.tracel.plugin.importer.coreprotect.translate.ImportPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reads a `CoreProtect` database into the logs, a batch at a time.
 *
 * Each batch lands with the rows it ended on, so a stopped server, a crash or `#stop` costs nothing: the next run
 * takes it from the rows after.
 *
 * Tables are bounded by what they held when the run began, so a `CoreProtect` still
 * writing beside us cannot keep it going for ever.
 *
 * @param slice runs one batch's write; whoever hands it in may make it wait for a rollback to finish first
 */
class CoreProtectImport(
    private val foreign: ForeignHistory,
    private val platform: ImportPlatform,
    private val slice: suspend (suspend () -> Unit) -> Unit,
) {
    /** What importing [database] would do, without doing it. */
    suspend fun outlook(database: CoreProtectDatabase): ImportOutlook = withContext(Dispatchers.IO) {
        val room = foreign.room(database.fingerprint())
        ImportOutlook(database.version(), room.mark, database.lastRows(), database.span(), room.ownSince)
    }

    /** Imports what is left of [database]. [progress] hears about every batch; [stop] is asked between them. */
    suspend fun run(
        database: CoreProtectDatabase,
        stop: () -> Boolean,
        progress: (done: Long, of: Long) -> Unit,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val source = database.fingerprint()
        val outlook = outlook(database)
        val translator = CoreProtectTranslator(
            database.tables(),
            platform,
            source,
            outlook.ownSince ?: started,
            database::skull,
            database::entity
        )
        val stream = CoreProtectStream(database, outlook.resumed?.rows.orEmpty(), outlook.lastRows)
        val total = stream.left

        var stopped = false
        while (true) {
            coroutineContext.ensureActive()
            if (stop()) {
                stopped = true
                break
            }
            val rows = stream.next(BATCH_ROWS)
            if (rows.isEmpty()) break
            val records = translator.translate(rows)
            val marks = stream.marks
            for ((mob, kind) in translator.newMobs()) foreign.noteKind(mob, kind)
            slice { foreign.append(source, marks, records) }
            progress(total - stream.left, total)
        }
        ImportOutcome(outlook, translator.tally, stopped, System.currentTimeMillis() - started)
    }

    private companion object {
        const val BATCH_ROWS = 4_000
    }
}
