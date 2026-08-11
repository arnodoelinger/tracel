package com.tracel.storage.ledger

import com.tracel.engine.concurrency.SingleWriterGuard
import com.tracel.engine.ledger.LotRepository
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.storage.holder.HolderCodec
import com.tracel.storage.schema.LotEdgesTable
import com.tracel.storage.schema.LotsTable
import com.tracel.storage.schema.PlacementsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin as innerJoinOn
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * `SQLite`-backed [LotRepository]. Every method is its own short-lived transaction rather than
 * one big one spanning a whole [com.tracel.engine.ledger.LotLedger] operation — that mirrors
 * the granularity the interface already had in the in-memory version, where a crash between
 * two repository calls was never something a caller could prevent, only something
 * [com.tracel.engine.journal.JournalExecutor]'s resumable stepping was built to tolerate for
 * rollback jobs specifically.
 *
 * See [SingleWriterGuard] for the other half of the crash-safety story: only one thread may
 * ever be mid-write here at a time.
 */
class SqliteLotRepository(private val db: Database) : LotRepository {
    private val writer = SingleWriterGuard()

    override fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot {
        writer.checkIn()
        return transaction(db) {
            val id = LotsTable.insert {
                it[material] = itemKey.material
                it[decoration] = itemKey.decoration?.hex
                it[LotsTable.quantity] = quantity.raw
                it[LotsTable.createdBy] = createdBy.raw
            } get LotsTable.id
            Lot(LotId(id), itemKey, quantity, createdBy)
        }
    }

    override fun lot(id: LotId): Lot = transaction(db) {
        LotsTable.selectAll().where { LotsTable.id eq id.raw }.single().toLot()
    }

    override fun recordEdge(edge: LotEdge) {
        writer.checkIn()
        transaction(db) {
            LotEdgesTable.insert { statement ->
                statement[child] = edge.child.raw
                statement[parent] = edge.parent.raw
                statement[quantity] = edge.quantity.raw
                when (edge) {
                    is LotEdge.Split -> statement[kind] = "SPLIT"
                    is LotEdge.Transform -> {
                        statement[kind] = "TRANSFORM"
                        statement[craftedBy] = edge.craftedBy.raw
                        statement[producedAt] = HolderCodec.encode(edge.producedAt)
                    }
                    is LotEdge.Compensate -> {
                        statement[kind] = "COMPENSATE"
                        statement[rollbackJob] = edge.rollbackJob.raw
                    }
                }
            }
        }
    }

    override fun edgesFrom(lotId: LotId): List<LotEdge> = transaction(db) {
        LotEdgesTable.selectAll().where { LotEdgesTable.parent eq lotId.raw }.map { it.toLotEdge() }
    }

    override fun edgesInto(lotId: LotId): List<LotEdge> = transaction(db) {
        LotEdgesTable.selectAll().where { LotEdgesTable.child eq lotId.raw }.map { it.toLotEdge() }
    }

    override fun accountQueue(holder: HolderId, itemKey: ItemKey): List<AccountLot> = transaction(db) {
        PlacementsTable.innerJoinOn(LotsTable, { lotId }, { id })
            .selectAll()
            .where {
                (PlacementsTable.holder eq HolderCodec.encode(holder)) and
                    (LotsTable.material eq itemKey.material) and
                    (LotsTable.decoration eq itemKey.decoration?.hex)
            }
            .orderBy(PlacementsTable.id)
            .map { it.toAccountLot(holder) }
    }

    override fun allPlacements(itemKey: ItemKey): List<AccountLot> = transaction(db) {
        PlacementsTable.innerJoinOn(LotsTable, { lotId }, { id })
            .selectAll()
            .where {
                (LotsTable.material eq itemKey.material) and (LotsTable.decoration eq itemKey.decoration?.hex)
            }
            .map { row -> row.toAccountLot(HolderCodec.decode(row[PlacementsTable.holder])) }
    }

    override fun currentHolderOf(lotId: LotId): HolderId? = transaction(db) {
        PlacementsTable.selectAll().where { PlacementsTable.lotId eq lotId.raw }
            .singleOrNull()
            ?.let { HolderCodec.decode(it[PlacementsTable.holder]) }
    }

    override fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot {
        writer.checkIn()
        return transaction(db) {
            val lot = LotsTable.selectAll().where { LotsTable.id eq lotId.raw }.single().toLot()
            val seq = PlacementsTable.insert {
                it[PlacementsTable.holder] = HolderCodec.encode(holder)
                it[PlacementsTable.lotId] = lotId.raw
                it[remaining] = quantity.raw
            } get PlacementsTable.id
            AccountLot(holder, lot, quantity, Seq(seq))
        }
    }

    override fun remove(holder: HolderId, lotId: LotId) {
        writer.checkIn()
        transaction(db) {
            PlacementsTable.deleteWhere {
                (PlacementsTable.holder eq HolderCodec.encode(holder)) and (PlacementsTable.lotId eq lotId.raw)
            }
        }
    }

    override fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity) {
        writer.checkIn()
        transaction(db) {
            val updated = PlacementsTable.update({
                (PlacementsTable.holder eq HolderCodec.encode(holder)) and (PlacementsTable.lotId eq retiredLotId.raw)
            }) {
                it[PlacementsTable.lotId] = newLotId.raw
                it[PlacementsTable.remaining] = remaining.raw
            }
            check(updated == 1) { "no placement of $retiredLotId at $holder" }
        }
    }

    private fun ResultRow.toLot(): Lot = Lot(
        LotId(this[LotsTable.id]),
        ItemKey(this[LotsTable.material], this[LotsTable.decoration]?.let(::ContentHash)),
        Quantity(this[LotsTable.quantity]),
        TxnId(this[LotsTable.createdBy]),
    )

    private fun ResultRow.toAccountLot(holder: HolderId): AccountLot = AccountLot(
        holder,
        toLot(),
        Quantity(this[PlacementsTable.remaining]),
        Seq(this[PlacementsTable.id]),
    )

    private fun ResultRow.toLotEdge(): LotEdge {
        val child = LotId(this[LotEdgesTable.child])
        val parent = LotId(this[LotEdgesTable.parent])
        val quantity = Quantity(this[LotEdgesTable.quantity])
        return when (val kind = this[LotEdgesTable.kind]) {
            "SPLIT" -> LotEdge.Split(child, parent, quantity)
            "TRANSFORM" -> LotEdge.Transform(
                child,
                parent,
                quantity,
                TxnId(this[LotEdgesTable.craftedBy] ?: error("TRANSFORM edge $child<-$parent missing crafted_by")),
                HolderCodec.decode(this[LotEdgesTable.producedAt] ?: error("TRANSFORM edge $child<-$parent missing produced_at")),
            )
            "COMPENSATE" -> LotEdge.Compensate(
                child,
                parent,
                quantity,
                RollbackJobId(this[LotEdgesTable.rollbackJob] ?: error("COMPENSATE edge $child<-$parent missing rollback_job")),
            )
            else -> error("unrecognized lot edge kind: $kind")
        }
    }
}
