package com.tracel.storage.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/** One row per [com.tracel.model.lot.Lot]. Lots are immutable, so nothing here is ever updated after insert. */
object LotsTable : Table("lots") {
    val id: Column<Long> = long("id").autoIncrement()
    val material: Column<String> = varchar("material", 255)
    val decoration: Column<String?> = varchar("decoration", 64).nullable()
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

    /** Set only when [kind] is `TRANSFORM` — the holder-encoded text a [com.tracel.storage.holder.HolderCodec] produces. */
    val producedAt: Column<String?> = text("produced_at").nullable()

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
    val holder: Column<String> = text("holder").index()
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
