package com.tracel.engine.world

import com.tracel.engine.log.TransactionLog
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.log.Seq
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange

/**
 * The append-only record of the world's shape changing: every block and entity change ever made, with who made it and
 * why. The sibling of [TransactionLog], which records material moving.
 *
 * Nothing is edited or removed once appended. Changes come back newest first.
 */
public interface WorldLog {
    /** Appends [change]. Throws if its sequence is already taken, because the log never overwrites. */
    public suspend fun append(change: WorldChange)

    /**
     * Appends one change for every edit in [edits] that actually changes its cell, each with its own sequence from
     * [nextSeqRange]. An edit whose shape stays the same writes nothing. Returns how many were written.
     */
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

    /** What happened at [pos], newest first, at most [limit] of it. */
    public suspend fun at(pos: BlockPos, limit: Int = 100): List<WorldChange>

    /** Changes that [filter] lets through, newest first, after its offset and up to its limit. */
    public suspend fun query(filter: LookupFilter): List<WorldChange>

    /**
     * What [filter] finds in this log and in [transactions] together, so that one lookup asks both. The world side is
     * skipped when [includeWorld] is `false`. With [structureEnds] a stored log hands back only the oldest and newest
     * change at each cell, which is all a rollback planner reads; this default answer ignores it.
     */
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
