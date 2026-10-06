package com.tracel.engine.rollback.lease.memory

import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext
import com.tracel.engine.rollback.lease.Leases
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.platform.concurrency.SingleWriterGuard

/** In-memory [Leases]. */
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryLeases(private val clock: () -> Long = System::currentTimeMillis) : Leases() {
    private val writer = SingleWriterGuard()
    private val holders = mutableMapOf<LotId, Entry>()

    private data class Entry(val job: RollbackJobId, val acquiredAtMillis: Long)

    override suspend fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId> {
        writer.checkIn()
        val conflicts = buildMap {
            for (lotId in lotIds) holders[lotId]?.job?.takeIf { it != job }?.let { put(lotId, it) }
        }
        if (conflicts.isNotEmpty()) return conflicts
        val now = clock()
        for (lotId in lotIds) holders[lotId] = Entry(job, now)
        return emptyMap()
    }

    override suspend fun release(job: RollbackJobId) {
        writer.checkIn()
        holders.keys.removeAll(lotsOf(job))
    }

    override suspend fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId> {
        writer.checkIn()
        val now = clock()
        val moved = lotsOf(from)
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

    private fun lotsOf(job: RollbackJobId): Set<LotId> = holders.filterValues { it.job == job }.keys
}
