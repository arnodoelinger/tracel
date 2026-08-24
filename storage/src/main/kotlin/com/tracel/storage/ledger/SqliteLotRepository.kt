package com.tracel.storage.ledger

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.engine.ledger.LotRepository
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge
import com.tracel.platform.storage.UnitOfWork
import com.tracel.storage.Storage
import com.tracel.storage.intern.Interning
import com.tracel.storage.schema.LotEdgesTable
import com.tracel.storage.schema.LotsTable
import com.tracel.storage.schema.PlacementsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin as innerJoinOn
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * `SQLite`-backed [LotRepository].
 *
 * A method called on its own is its own transaction; a method called inside a
 * [UnitOfWork.atomically] joins that one instead, which is how a whole
 * [com.tracel.engine.ledger.LotLedger] operation collapses into a single commit.
 *
 * Lots are cached outright. A lot row is written once at creation and never updated afterwards —
 * only its placement moves — so a cache of them cannot go stale, and `itemKeyOf` / `quantityOf`
 * (which a rollback asks for once per step, per lot, several times over) stop being queries at all.
 */
class SqliteLotRepository(private val storage: Storage) : LotRepository, UnitOfWork by storage {
    private val lots: Cache<LotId, Lot> = Caffeine.newBuilder().maximumSize(100_000).build()

    override suspend fun createLot(itemKey: ItemKey, quantity: Quantity, createdBy: TxnId): Lot = storage.write {
        val itemKeyId = Interning.internItemKey(itemKey)
        val id = LotsTable.insert {
            it[LotsTable.itemKeyId] = itemKeyId
            it[LotsTable.quantity] = quantity.raw
            it[LotsTable.createdBy] = createdBy.raw
        } get LotsTable.id
        Lot(LotId(id), itemKey, quantity, createdBy).also { lots.put(it.id, it) }
    }

    override suspend fun lot(id: LotId): Lot = lots.getIfPresent(id) ?: storage.read {
        LotsTable.selectAll().where { LotsTable.id eq id.raw }.single().toLot().also { lots.put(id, it) }
    }

    override suspend fun recordEdge(edge: LotEdge) {
        storage.write {
            LotEdgesTable.insert { statement ->
                statement[child] = edge.child.raw
                statement[parent] = edge.parent.raw
                statement[quantity] = edge.quantity.raw
                when (edge) {
                    is LotEdge.Split -> statement[kind] = "SPLIT"
                    is LotEdge.Transform -> {
                        statement[kind] = "TRANSFORM"
                        statement[craftedBy] = edge.craftedBy.raw
                        statement[producedAtHolderId] = Interning.internHolder(edge.producedAt)
                    }
                    is LotEdge.Compensate -> {
                        statement[kind] = "COMPENSATE"
                        statement[rollbackJob] = edge.rollbackJob.raw
                    }
                }
            }
        }
    }

    override suspend fun edgesFrom(lotId: LotId): List<LotEdge> = storage.read {
        LotEdgesTable.selectAll().where { LotEdgesTable.parent eq lotId.raw }.map { it.toLotEdge() }
    }

    override suspend fun edgesInto(lotId: LotId): List<LotEdge> = storage.read {
        LotEdgesTable.selectAll().where { LotEdgesTable.child eq lotId.raw }.map { it.toLotEdge() }
    }

    override suspend fun accountQueue(holder: HolderId, itemKey: ItemKey, limit: Int): List<AccountLot> = storage.read {
        val holderId = Interning.findHolderId(holder) ?: return@read emptyList()
        val itemKeyId = Interning.findItemKeyId(itemKey) ?: return@read emptyList()

        PlacementsTable.innerJoinOn(LotsTable, { lotId }, { id })
            .selectAll()
            .where { (PlacementsTable.holderId eq holderId) and (LotsTable.itemKeyId eq itemKeyId) }
            .orderBy(PlacementsTable.id)
            .let { query -> if (limit == Int.MAX_VALUE) query else query.limit(limit) }
            .map { it.toAccountLot(holder) }
    }

    override suspend fun totalOf(holder: HolderId, itemKey: ItemKey): Long = storage.read {
        val holderId = Interning.findHolderId(holder) ?: return@read 0L
        val itemKeyId = Interning.findItemKeyId(itemKey) ?: return@read 0L

        val total = PlacementsTable.remaining.sum()
        PlacementsTable.innerJoinOn(LotsTable, { lotId }, { id })
            .select(total)
            .where { (PlacementsTable.holderId eq holderId) and (LotsTable.itemKeyId eq itemKeyId) }
            .firstOrNull()
            ?.get(total)
            ?: 0L
    }

    override suspend fun totalsAt(holder: HolderId): Map<ItemKey, Long> = storage.read {
        val holderId = Interning.findHolderId(holder) ?: return@read emptyMap()

        val total = PlacementsTable.remaining.sum()
        PlacementsTable.innerJoinOn(LotsTable, { lotId }, { id })
            .select(LotsTable.itemKeyId, total)
            .where { PlacementsTable.holderId eq holderId }
            .groupBy(LotsTable.itemKeyId)
            .associate { Interning.resolveItemKey(it[LotsTable.itemKeyId]) to (it[total] ?: 0L) }
    }

    override suspend fun placementOf(holder: HolderId, lotId: LotId): AccountLot? = storage.read {
        val holderId = Interning.findHolderId(holder) ?: return@read null

        PlacementsTable.innerJoinOn(LotsTable, { PlacementsTable.lotId }, { id })
            .selectAll()
            .where { (PlacementsTable.holderId eq holderId) and (PlacementsTable.lotId eq lotId.raw) }
            .firstOrNull()
            ?.toAccountLot(holder)
    }

    override suspend fun allPlacements(itemKey: ItemKey): List<AccountLot> = storage.read {
        val itemKeyId = Interning.findItemKeyId(itemKey) ?: return@read emptyList()

        PlacementsTable.innerJoinOn(LotsTable, { lotId }, { id })
            .selectAll()
            .where { LotsTable.itemKeyId eq itemKeyId }
            .map { row -> row.toAccountLot(Interning.resolveHolder(row[PlacementsTable.holderId])) }
    }

    override suspend fun placementsAt(holder: HolderId): List<AccountLot> = storage.read {
        val holderId = Interning.findHolderId(holder) ?: return@read emptyList()

        PlacementsTable.innerJoinOn(LotsTable, { lotId }, { id })
            .selectAll()
            .where { PlacementsTable.holderId eq holderId }
            .map { it.toAccountLot(holder) }
    }

    override suspend fun currentHolderOf(lotId: LotId): HolderId? = storage.read {
        PlacementsTable.selectAll().where { PlacementsTable.lotId eq lotId.raw }
            .singleOrNull()
            ?.let { Interning.resolveHolder(it[PlacementsTable.holderId]) }
    }

    override suspend fun place(holder: HolderId, lotId: LotId, quantity: Quantity): AccountLot = storage.write {
        val lot = lot(lotId)
        val seq = PlacementsTable.insert {
            it[holderId] = Interning.internHolder(holder)
            it[PlacementsTable.lotId] = lotId.raw
            it[remaining] = quantity.raw
        } get PlacementsTable.id
        AccountLot(holder, lot, quantity, Seq(seq))
    }

    override suspend fun remove(holder: HolderId, lotId: LotId) {
        storage.write {
            val holderId = Interning.findHolderId(holder) ?: return@write
            PlacementsTable.deleteWhere { (PlacementsTable.holderId eq holderId) and (PlacementsTable.lotId eq lotId.raw) }
        }
    }

    override suspend fun replace(holder: HolderId, retiredLotId: LotId, newLotId: LotId, remaining: Quantity) {
        storage.write {
            val holderId = Interning.findHolderId(holder)
                ?: error("no placement of $retiredLotId at $holder")
            val updated = PlacementsTable.update({
                (PlacementsTable.holderId eq holderId) and (PlacementsTable.lotId eq retiredLotId.raw)
            }) {
                it[PlacementsTable.lotId] = newLotId.raw
                it[PlacementsTable.remaining] = remaining.raw
            }
            check(updated == 1) { "no placement of $retiredLotId at $holder" }
        }
    }

    /** Drops every cached lot — for when something deleted rows out from under this repository. */
    fun forget() {
        lots.invalidateAll()
    }

    private fun ResultRow.toLot(): Lot = Lot(
        LotId(this[LotsTable.id]),
        Interning.resolveItemKey(this[LotsTable.itemKeyId]),
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
                Interning.resolveHolder(
                    this[LotEdgesTable.producedAtHolderId] ?: error("TRANSFORM edge $child<-$parent missing produced_at")
                ),
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
