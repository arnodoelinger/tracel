package com.tracel.storage.ports.ledger

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.util.eachRow
import com.tracel.engine.ownership.LotLeaseRegistry as LotLeaseRegistryPort

/**
 * The durable half of ownership. A lease record survives the process dying.
 *
 * Kept twice — by lot, and by job — because both directions get asked. `lease | lot` answers
 * "who holds this", which [tryReserve] needs per lot; `leaseJob | job | lot` answers "what does
 * this job hold", which [release] and [transfer] need without scanning every lease on the disk.
 */
class LotLeaseRegistry(private val storage: TracelStorage) : LotLeaseRegistryPort() {
    /**
     * Try to reserve the given lots for the given job.
     *
     * @return a map of lots that are already held by other jobs.
     */
    override suspend fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId> =
        storage.write {
            val conflicts = HashMap<LotId, RollbackJobId>()
            val held = HashSet<LotId>()
            for (lotId in lotIds) {
                val existing = get(Keys.lease(lotId.raw)) ?: continue
                val owner = RollbackJobId(Records.leaseJobId(existing))
                if (owner != job) conflicts[lotId] = owner else held += lotId
            }
            if (conflicts.isNotEmpty()) return@write conflicts

            val now = System.currentTimeMillis()
            for (lotId in lotIds) {
                put(Keys.lease(lotId.raw), Records.lease(job.raw, now))
                if (lotId !in held) put(Keys.leaseJob(job.raw, lotId.raw), EMPTY)
            }
            emptyMap()
        }

    /** Release all lots held by the given job. */
    override suspend fun release(job: RollbackJobId) {
        storage.write {
            val lotIds = lotsOf(job)
            for (lotId in lotIds) {
                delete(Keys.lease(lotId))
                delete(Keys.leaseJob(job.raw, lotId))
            }
        }
    }

    /** Transfer all lots held by one job to another. */
    override suspend fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId> = storage.write {
        val lotIds = lotsOf(from)
        if (lotIds.isEmpty()) return@write emptySet()
        val now = System.currentTimeMillis()
        for (lotId in lotIds) {
            put(Keys.lease(lotId), Records.lease(to.raw, now))
            delete(Keys.leaseJob(from.raw, lotId))
            put(Keys.leaseJob(to.raw, lotId), EMPTY)
        }
        lotIds.mapTo(HashSet(), ::LotId)
    }

    /** Reap leases that have been abandoned for too long. */
    override suspend fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId> = storage.write {
        val threshold = nowMillis - maxAgeMillis
        val abandoned = HashSet<RollbackJobId>()
        val stale = ArrayList<Pair<Long, Long>>()
        eachRow(Keys.leasePrefix()) { cursor ->
            val value = cursor.value()
            if (Records.leaseAcquiredAt(value) >= threshold) return@eachRow
            val jobId = Records.leaseJobId(value)
            abandoned += RollbackJobId(jobId)
            stale += jobId to KeyReader.u64(cursor.key(), 1)
        }
        for ((jobId, lotId) in stale) {
            delete(Keys.lease(lotId))
            delete(Keys.leaseJob(jobId, lotId))
        }
        abandoned
    }

    private fun StorageUnit.lotsOf(job: RollbackJobId): List<Long> {
        val out = ArrayList<Long>()
        eachRow(Keys.leaseJobPrefix(job.raw)) { cursor -> out += KeyReader.u64(cursor.key(), 9) }
        return out
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
