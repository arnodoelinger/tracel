package com.tracel.storage.ownership

import com.tracel.engine.ownership.LotLeaseRegistry
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.Storage
import com.tracel.storage.schema.LotLeasesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * `SQLite`-backed [LotLeaseRegistry] — the durable half of ownership. A row in
 * [LotLeasesTable] survives the process dying, unlike [com.tracel.engine.ownership.InMemoryLotLeaseRegistry]'s
 * plain map: a job mid-apply when the server crashes still holds its lots after a restart.
 */
class SqliteLotLeaseRegistry(private val storage: Storage) : LotLeaseRegistry() {
    override suspend fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId> =
        storage.write {
            val rawIds = lotIds.map { it.raw }
            val existing = LotLeasesTable.selectAll().where { LotLeasesTable.lotId inList rawIds }
                .associate { LotId(it[LotLeasesTable.lotId]) to RollbackJobId(it[LotLeasesTable.jobId]) }

            val conflicts = existing.filterValues { it != job }
            if (conflicts.isNotEmpty()) return@write conflicts

            val now = System.currentTimeMillis()
            for (lotId in lotIds) {
                if (lotId in existing) {
                    LotLeasesTable.update({ LotLeasesTable.lotId eq lotId.raw }) { it[acquiredAtMillis] = now }
                } else {
                    LotLeasesTable.insert {
                        it[LotLeasesTable.lotId] = lotId.raw
                        it[jobId] = job.raw
                        it[acquiredAtMillis] = now
                    }
                }
            }
            emptyMap()
        }

    override suspend fun release(job: RollbackJobId) {
        storage.write {
            LotLeasesTable.deleteWhere { jobId eq job.raw }
        }
    }

    override suspend fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId> =
        storage.write {
            val lotIds = LotLeasesTable.selectAll().where { LotLeasesTable.jobId eq from.raw }
                .map { LotId(it[LotLeasesTable.lotId]) }
                .toSet()
            if (lotIds.isNotEmpty()) {
                val now = System.currentTimeMillis()
                LotLeasesTable.update({ LotLeasesTable.jobId eq from.raw }) {
                    it[jobId] = to.raw
                    it[acquiredAtMillis] = now
                }
            }
            lotIds
        }

    override suspend fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId> =
        storage.write {
            val threshold = nowMillis - maxAgeMillis
            val abandoned = LotLeasesTable.selectAll().where { LotLeasesTable.acquiredAtMillis less threshold }
                .map { RollbackJobId(it[LotLeasesTable.jobId]) }
                .toSet()
            if (abandoned.isNotEmpty()) {
                LotLeasesTable.deleteWhere { jobId inList abandoned.map { job -> job.raw } }
            }
            abandoned
        }
}
