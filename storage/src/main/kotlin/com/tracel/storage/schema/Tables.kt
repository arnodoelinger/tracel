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
    val epochMillis: Column<Long> = long("epoch_millis")
    val cause: Column<String> = varchar("cause", 32)
    val causedByHolderId: Column<Long?> = long("caused_by_holder_id").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/** One row per [com.tracel.model.flow.Flow] belonging to a [TransactionsTable] row. */
object FlowsTable : Table("flows") {
    val txnId: Column<Long> = long("txn_id").index()
    val idx: Column<Int> = integer("idx")
    val itemKeyId: Column<Long> = long("item_key_id")
    val quantity: Column<Long> = long("quantity")
    val sourceHolderId: Column<Long> = long("source_holder_id")
    val destinationHolderId: Column<Long> = long("destination_holder_id")
    val kind: Column<String> = varchar("kind", 16)

    override val primaryKey: PrimaryKey = PrimaryKey(txnId, idx)
}

/**
 * Persisted id allocation — see [com.tracel.storage.counters.SqliteCounters]. Without this, a
 * counter that starts fresh at 1 on every plugin restart collides with [TransactionsTable] rows
 * a previous session already wrote, and [com.tracel.engine.log.TransactionLog.append]'s
 * duplicate-id check throws for every capture attempt until the counter catches back up —
 * caught live by actually restarting a real server with real data already in it, not by a test.
 */
object CountersTable : Table("id_counters") {
    val name: Column<String> = varchar("name", 16)
    val nextValue: Column<Long> = long("next_value")

    override val primaryKey: PrimaryKey = PrimaryKey(name)
}
