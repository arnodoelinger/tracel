package com.tracel.engine.ownership

import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** In-memory [LotLeaseRegistry]. */
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryLotLeaseRegistry : LotLeaseRegistry() {
    private val writer = SingleWriterGuard()
    private val holders = mutableMapOf<LotId, Entry>()

    override suspend fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId> {
        writer.checkIn()
        val conflicts = lotIds.mapNotNull { lotId ->
            holders[lotId]?.takeIf { it.job != job }?.let { lotId to it.job }
        }.toMap()
        if (conflicts.isNotEmpty()) return conflicts
        val now = System.currentTimeMillis()
        for (lotId in lotIds) holders[lotId] = Entry(job, now)
        return emptyMap()
    }

    override suspend fun release(job: RollbackJobId) {
        writer.checkIn()
        holders.values.removeAll { it.job == job }
    }

    override suspend fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId> {
        writer.checkIn()
        val now = System.currentTimeMillis()
        val moved = holders.filterValues { it.job == from }.keys
        for (lotId in moved) holders[lotId] = Entry(to, now)
        return moved
    }

    override suspend fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId> {
        writer.checkIn()
        val abandoned = holders.values
            .filter { nowMillis - it.acquiredAtMillis > maxAgeMillis }
            .mapTo(mutableSetOf()) { it.job }
        holders.values.removeAll { it.job in abandoned }
        return abandoned
    }

    private data class Entry(val job: RollbackJobId, val acquiredAtMillis: Long)
}
