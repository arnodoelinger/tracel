package com.tracel.engine.world

import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.TransactionLog
import com.tracel.model.id.Seq
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange

/**
 * The append-only record of the world's shape changing, alongside
 * [TransactionLog]'s record of material moving.
 */
public interface WorldLog {
    /** Appends [change]. Throws if its sequence is already taken. */
    public suspend fun append(change: WorldChange)

    /** Appends every edit of one event, and returns how many it wrote. */
    public suspend fun appendAll(edits: BlockEdits, nextSeqRange: suspend (Int) -> Seq): Int {
        var written = 0
        for ((at, before, after) in edits.edits) {
            if (before == after) continue
            append(
                WorldChange(
                    nextSeqRange(1), edits.action, edits.cause, edits.causedBy, edits.epochMillis, at,
                    ChangeSubject.Block(before, after),
                )
            )
            written++
        }
        return written
    }

    /** Everything that ever happened at [pos], newest first, capped at [limit]. */
    public suspend fun at(pos: BlockPos, limit: Int = 100): List<WorldChange>

    /** Changes matching [filter], newest first. */
    public suspend fun query(filter: LookupFilter): List<WorldChange>

    /** Both logs from one index walk. */
    public suspend fun queryTogether(
        transactions: TransactionLog,
        filter: LookupFilter,
        includeWorld: Boolean = true,
        structureEnds: Boolean = false,
    ): Pair<List<WorldChange>, List<Transaction>> {
        val world = if (!includeWorld) emptyList() else query(filter)
        return world to transactions.query(filter)
    }
}
