package com.tracel.storage.ownership

import com.tracel.engine.ownership.LotLeaseRegistry
import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.schema.LotLeasesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * `SQLite`-backed [LotLeaseRegistry] — the durable half of ownership. A row in
 * [LotLeasesTable] survives the process dying, unlike [com.tracel.engine.ownership.InMemoryLotLeaseRegistry]'s
 * plain map: a job mid-apply when the server crashes still holds its lots after a restart.
 */
class SqliteLotLeaseRegistry(private val db: Database) : LotLeaseRegistry() {
    private val writer = SingleWriterGuard()

    override fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId> {
        writer.checkIn()
        return transaction(db) {
            val rawIds = lotIds.map { it.raw }
            val existing = LotLeasesTable.selectAll().where { LotLeasesTable.lotId inList rawIds }
                .associate { LotId(it[LotLeasesTable.lotId]) to RollbackJobId(it[LotLeasesTable.jobId]) }

            val conflicts = existing.filterValues { it != job }
            if (conflicts.isNotEmpty()) return@transaction conflicts

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
    }

    override fun release(job: RollbackJobId) {
        writer.checkIn()
        transaction(db) {
            LotLeasesTable.deleteWhere { jobId eq job.raw }
        }
    }

    override fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId> {
        writer.checkIn()
        return transaction(db) {
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
    }

    override fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId> {
        writer.checkIn()
        return transaction(db) {
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
}
