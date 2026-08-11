package com.tracel.engine.ownership

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** In-memory [LotLeaseRegistry]. */
public class InMemoryLotLeaseRegistry : LotLeaseRegistry() {
    private data class Entry(val job: RollbackJobId, val acquiredAtMillis: Long)

    private val writer = SingleWriterGuard()
    private val holders = mutableMapOf<LotId, Entry>()

    override fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId> {
        writer.checkIn()
        val conflicts = lotIds.mapNotNull { lotId -> holders[lotId]?.takeIf { it.job != job }?.let { lotId to it.job } }.toMap()
        if (conflicts.isNotEmpty()) return conflicts

        val now = System.currentTimeMillis()
        for (lotId in lotIds) holders[lotId] = Entry(job, now)
        return emptyMap()
    }

    override fun release(job: RollbackJobId) {
        writer.checkIn()
        holders.entries.removeAll { it.value.job == job }
    }

    override fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId> {
        writer.checkIn()
        val now = System.currentTimeMillis()
        val toTransfer = holders.filterValues { it.job == from }.keys.toSet()
        for (lotId in toTransfer) holders[lotId] = Entry(to, now)
        return toTransfer
    }

    override fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId> {
        writer.checkIn()
        val abandoned = holders.values.filter { nowMillis - it.acquiredAtMillis > maxAgeMillis }.mapTo(mutableSetOf()) { it.job }
        holders.entries.removeAll { it.value.job in abandoned }
        return abandoned
    }
}
