package com.tracel.engine.world

import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.TransactionLog
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldChange

/**
 * The append-only record of the world's shape changing, alongside
 * [TransactionLog]'s record of material moving.
 */
public interface WorldLog {
    /** Appends [change]. Throws if its sequence is already taken. */
    public suspend fun append(change: WorldChange)

    /** Everything that ever happened at [pos], newest first, capped at [limit]. */
    public suspend fun at(pos: BlockPos, limit: Int = 100): List<WorldChange>

    /** Changes matching [filter], newest first. */
    public suspend fun query(filter: LookupFilter): List<WorldChange>

    /** Both logs from one index walk. */
    public suspend fun queryTogether(
        transactions: TransactionLog,
        filter: LookupFilter,
    ): Pair<List<WorldChange>, List<Transaction>> = query(filter) to transactions.query(filter)
}
