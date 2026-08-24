package com.tracel.storage.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * Interned [com.tracel.model.item.ItemKey]s. The main source of storage savings once real
 * capture traffic starts: a million `minecraft:diamond` rows collapse to one row and a
 * varint foreign key everywhere else, instead of repeating "minecraft:diamond" as text on
 * every lot and every placement.
 */
object ItemKeysTable : Table("item_keys") {
    val id: Column<Long> = long("id").autoIncrement()
    val material: Column<String> = varchar("material", 255).index()
    val decoration: Column<String?> = varchar("decoration", 64).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * Interned [com.tracel.storage.holder.HolderCodec]-encoded [com.tracel.model.holder.HolderId]s.
 * A hopper sitting in one spot generates thousands of placements referencing the exact same
 * holder — interning it once is the other half of the savings [ItemKeysTable] gives.
 */
object HoldersTable : Table("holders") {
    val id: Column<Long> = long("id").autoIncrement()
    val encoded: Column<String> = text("encoded").index()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/** One row per [com.tracel.model.lot.Lot]. Lots are immutable, so nothing here is ever updated after insert. */
object LotsTable : Table("lots") {
    val id: Column<Long> = long("id").autoIncrement()
    val itemKeyId: Column<Long> = long("item_key_id").index()
    val quantity: Column<Long> = long("quantity")
    val createdBy: Column<Long> = long("created_by")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * One row per [com.tracel.model.lot.LotEdge]. All three variants share one table — [kind]
 * says which — since an edge is always looked up by [child] or [parent] alone, never
 * filtered by variant-specific fields.
 */
object LotEdgesTable : Table("lot_edges") {
    val id: Column<Long> = long("id").autoIncrement()
    val kind: Column<String> = varchar("kind", 16)
    val child: Column<Long> = long("child").index()
    val parent: Column<Long> = long("parent").index()
    val quantity: Column<Long> = long("quantity")

    /** Set only when [kind] is `TRANSFORM`. */
    val craftedBy: Column<Long?> = long("crafted_by").nullable()

    /** Set only when [kind] is `TRANSFORM` — the interned holder the craft's output landed at. */
    val producedAtHolderId: Column<Long?> = long("produced_at_holder_id").nullable()

    /** Set only when [kind] is `COMPENSATE`. */
    val rollbackJob: Column<Long?> = long("rollback_job").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * One row per FIFO queue entry: [id] (autoincrement) doubles as the entry's [com.tracel.model.id.Seq] — `SQLite`
 * assigning it is what replaces the in-memory repository's own counter.
 */
object PlacementsTable : Table("placements") {
    val id: Column<Long> = long("id").autoIncrement()
    val holderId: Column<Long> = long("holder_id").index()
    val lotId: Column<Long> = long("lot_id").index()
    val remaining: Column<Long> = long("remaining")

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    init {
        index("placements_holder_lot", isUnique = false, holderId, lotId)
    }
}

/** Which steps of which rollback jobs have already run — see [com.tracel.engine.journal.Journal]. */
object JournalProgressTable : Table("journal_progress") {
    val jobId: Column<Long> = long("job_id")
    val stepIndex: Column<Int> = integer("step_index")

    override val primaryKey: PrimaryKey = PrimaryKey(jobId, stepIndex)
}

/**
 * Which job currently holds the exclusive right to touch each lot — see
 * [com.tracel.engine.ownership.LotLeaseRegistry]. A row surviving a crash is the entire point:
 * a job that was mid-apply when the process died still holds its lots on restart, so nothing
 * else can start touching them out from under a resuming job.
 */
object LotLeasesTable : Table("lot_leases") {
    val lotId: Column<Long> = long("lot_id")
    val jobId: Column<Long> = long("job_id").index()

    /**
     * Set on every [com.tracel.engine.ownership.LotLeaseRegistry.acquire] / `extend` — a job still
     * actively renewing its lease is not abandoned, no matter how old the row itself is.
     */
    val acquiredAtMillis: Column<Long> = long("acquired_at_millis")

    override val primaryKey: PrimaryKey = PrimaryKey(lotId)
}

/**
 * One row per [com.tracel.model.transaction.Transaction] — the append-only log
 * [com.tracel.engine.log.TransactionLog] persists. [PlacementsTable] / [LotsTable] / [LotEdgesTable]
 * are the queryable projection of what this log implies about current state; this table is
 * what actually happened, in order.
 */
object TransactionsTable : Table("transactions") {
    val id: Column<Long> = long("id")
    val seq: Column<Long> = long("seq").index()
    val epochMillis: Column<Long> = long("epoch_millis").index()
    val cause: Column<String> = varchar("cause", 32)
    val causedByHolderId: Column<Long?> = long("caused_by_holder_id").nullable().index()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/** One row per [com.tracel.model.flow.Flow] belonging to a [TransactionsTable] row. */
object FlowsTable : Table("flows") {
    val txnId: Column<Long> = long("txn_id").index()
    val idx: Column<Int> = integer("idx")
    val itemKeyId: Column<Long> = long("item_key_id").index()
    val quantity: Column<Long> = long("quantity")
    val sourceHolderId: Column<Long> = long("source_holder_id").index()
    val destinationHolderId: Column<Long> = long("destination_holder_id").index()
    val kind: Column<String> = varchar("kind", 16)

    override val primaryKey: PrimaryKey = PrimaryKey(txnId, idx)
}

/**
 * One row per [com.tracel.engine.rollback.RollbackJobRecord] — what
 * [com.tracel.engine.rollback.involution.InvolutionPlanner] needs to reverse an already-applied job.
 */
object RollbackJobsTable : Table("rollback_jobs") {
    val jobId: Column<Long> = long("job_id")
    val restoreToHolderId: Column<Long> = long("restore_to_holder_id")

    override val primaryKey: PrimaryKey = PrimaryKey(jobId)
}

/** One row per [com.tracel.engine.rollback.RollbackStep] belonging to a [RollbackJobsTable] row. */
object RollbackStepsTable : Table("rollback_steps") {
    val jobId: Column<Long> = long("job_id").index()
    val idx: Column<Int> = integer("idx")
    val kind: Column<String> = varchar("kind", 16)

    /** [com.tracel.engine.rollback.RollbackStep.Take]/`Mint`/`Debt`'s traced lot. */
    val lotId: Column<Long?> = long("lot_id").nullable()

    /** [com.tracel.engine.rollback.RollbackStep.Take]/`Mint`/`Debt`'s quantity. */
    val quantity: Column<Long?> = long("quantity").nullable()

    /** [com.tracel.engine.rollback.RollbackStep.Take.holder] or [com.tracel.engine.rollback.RollbackStep.Unmake.holder]. */
    val holderId: Column<Long?> = long("holder_id").nullable()

    /** Set only when [kind] is `MINT` — [com.tracel.engine.rollback.RollbackStep.Mint.reason]. */
    val reason: Column<String?> = varchar("reason", 16).nullable()

    /** Set only when [kind] is `DEBT` — [com.tracel.engine.rollback.RollbackStep.Debt.player]. */
    val playerUuid: Column<String?> = varchar("player_uuid", 36).nullable()

    /** Set only when [kind] is `UNMAKE` — [com.tracel.engine.rollback.RollbackStep.Unmake.outputLot]. */
    val outputLot: Column<Long?> = long("output_lot").nullable()

    /** Set only when [kind] is `UNMAKE` — [com.tracel.engine.rollback.RollbackStep.Unmake.craftedBy]. */
    val craftedBy: Column<Long?> = long("crafted_by").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(jobId, idx)
}

/** One row per [com.tracel.engine.rollback.LotContribution] belonging to a [RollbackStepsTable] `UNMAKE` row. */
object RollbackStepInputsTable : Table("rollback_step_inputs") {
    val jobId: Column<Long> = long("job_id")
    val stepIdx: Column<Int> = integer("step_idx")
    val inputIdx: Column<Int> = integer("input_idx")
    val lotId: Column<Long> = long("lot_id")
    val quantity: Column<Long> = long("quantity")

    override val primaryKey: PrimaryKey = PrimaryKey(jobId, stepIdx, inputIdx)
}

/**
 * Which steps of which job undos have already run — the involution-side twin of
 * [JournalProgressTable].
 */
object InvolutionProgressTable : Table("involution_progress") {
    val jobId: Column<Long> = long("job_id")
    val stepIndex: Column<Int> = integer("step_index")

    override val primaryKey: PrimaryKey = PrimaryKey(jobId, stepIndex)
}

/** Material owed to a specific offline player. */
object PendingDeliveriesTable : Table("pending_deliveries") {
    val id: Column<Long> = long("id").autoIncrement()
    val playerUuid: Column<String> = varchar("player_uuid", 36).index()
    val itemKeyId: Column<Long> = long("item_key_id")
    val delta: Column<Long> = long("delta")
    val jobId: Column<Long> = long("job_id")
    val createdMillis: Column<Long> = long("created_millis")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/** Persisted id allocation. */
object CountersTable : Table("id_counters") {
    val name: Column<String> = varchar("name", 16)
    val nextValue: Column<Long> = long("next_value")

    override val primaryKey: PrimaryKey = PrimaryKey(name)
}
