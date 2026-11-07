package com.tracel.storage.history

import com.tracel.engine.log.TransactionLog
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.world.WorldLog
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.log.Seq
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.RawBlockEdits
import com.tracel.storage.chrono.*
import com.tracel.storage.chrono.query.Found
import com.tracel.storage.codec.Extras

/**
 * The world-change log, kept by place: every block and entity change goes into its tile's history, as [Chrono]
 * keeps it, and every lookup is answered from there.
 */
class StoredWorldLog(storage: TracelStorage, chrono: Chrono = Chrono.of(storage)) : AbstractChronoLog(storage, chrono),
    WorldLog {
    override suspend fun append(change: WorldChange) {
        storage.write {
            val worldId = interning.internWorld(this, change.at.world)
            val (_, x, y, z) = change.at
            val tile = Tile.at(worldId, x, z)
            val cell = Cell.of(x, y, z).packed
            val actor = change.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val kind = ChronoKinds.of(change.cause, change.action)
            val seq = change.seq.raw
            when (val subject = change.subject) {
                is ChangeSubject.Block -> {
                    val writer = chrono.writer(this, Lane.BLOCKS)
                    refuseTwice(this, writer.holds(seq), tile, Lane.BLOCKS, change.seq)
                    writer.add(
                        tile,
                        seq,
                        change.epochMillis,
                        cell,
                        actor,
                        kind,
                        interning.internBlockData(this, subject.before.data),
                        interning.internBlockData(this, subject.after.data),
                        Extras.block(subject.before.extras),
                        Extras.block(subject.after.extras),
                    )
                }

                is ChangeSubject.Entity -> {
                    val writer = chrono.writer(this, Lane.ENTITIES)
                    refuseTwice(this, writer.holds(seq), tile, Lane.ENTITIES, change.seq)
                    writer.addEntity(
                        tile,
                        seq,
                        change.epochMillis,
                        cell,
                        actor,
                        kind,
                        interning.internEntityType(this, subject.type),
                        subject.entity.mostSignificantBits,
                        subject.entity.leastSignificantBits,
                        Extras.shape(subject.before),
                        Extras.shape(subject.after),
                    )
                }
            }
        }
    }

    override suspend fun appendAll(edits: BlockEdits, nextSeqRange: suspend (Int) -> Seq): Int {
        val real = edits.edits.filter { it.before != it.after }
        if (real.isEmpty()) return 0
        val base = nextSeqRange(real.size).raw
        storage.write {
            val writer = chrono.writer(this, Lane.BLOCKS)
            val actor = edits.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val kind = ChronoKinds.of(edits.cause, edits.action)
            for ((i, edit) in real.withIndex()) {
                val (world, x, y, z) = edit.at
                writer.add(
                    Tile.at(interning.internWorld(this, world), x, z),
                    base + i,
                    edits.epochMillis,
                    Cell.of(x, y, z).packed,
                    actor,
                    kind,
                    interning.internBlockData(this, edit.before.data),
                    interning.internBlockData(this, edit.after.data),
                    Extras.block(edit.before.extras),
                    Extras.block(edit.after.extras),
                )
            }
        }
        return real.size
    }

    suspend fun appendRaw(raw: RawBlockEdits, nextSeqRange: suspend (Int) -> Seq): Int {
        val n = raw.count
        if (n == 0) return 0
        val base = nextSeqRange(n).raw
        storage.write {
            val writer = chrono.writer(this, Lane.BLOCKS)
            val kind = ChronoKinds.of(raw.cause, raw.action)
            val c = raw.coordinates
            for (i in 0 until n) {
                val x = c[i * 3]
                val y = c[i * 3 + 1]
                val z = c[i * 3 + 2]
                writer.add(
                    Tile.at(raw.worldId, x, z),
                    base + i,
                    raw.epochMillis,
                    Cell.of(x, y, z).packed,
                    raw.causedById,
                    kind,
                    raw.befores[i],
                    raw.afters[i],
                    null,
                    null,
                )
            }
        }
        return n
    }

    override suspend fun at(pos: BlockPos, limit: Int): List<WorldChange> = storage.read {
        val worldId = interning.findWorldId(this, pos.world) ?: return@read emptyList()
        val tile = Tile.at(worldId, pos.x, pos.z)
        val cell = Cell.of(pos.x, pos.y, pos.z)
        val reader = chrono.reader(this)
        val found = Lane.WORLD.map { lane -> lane to Found(tile, reader.history(tile, cell, limit, lane)) }
        Changes(interning, this).newestFirst(found, 0, limit)
    }

    override suspend fun query(filter: LookupFilter): List<WorldChange> =
        storage.read { select(this, filter, ends = false) }

    override suspend fun queryTogether(
        transactions: TransactionLog,
        filter: LookupFilter,
        includeWorld: Boolean,
        structureEnds: Boolean,
    ): Pair<List<WorldChange>, List<Transaction>> {
        val world = if (!includeWorld) emptyList() else storage.read { select(this, filter, structureEnds) }
        val log = transactions as? StoredTransactionLog ?: return world to transactions.query(filter)
        return world to storage.read { log.select(this, filter, withLots = true) }
    }

    private fun refuseTwice(unit: StorageUnit, pending: Boolean, tile: Tile, lane: Lane, seq: Seq) {
        check(
            !pending && !chrono.holds(
                unit,
                tile,
                lane,
                seq.raw
            )
        ) { "world change at $seq already appended; the log is append-only." }
    }

    private fun select(unit: StorageUnit, filter: LookupFilter, ends: Boolean): List<WorldChange> {
        val plan = LookupPlan.of(unit, interning, filter) ?: return emptyList()
        return Changes(interning, unit).newestFirst(plan.run(chrono.reader(unit), ends), filter.offset, filter.limit)
    }
}
