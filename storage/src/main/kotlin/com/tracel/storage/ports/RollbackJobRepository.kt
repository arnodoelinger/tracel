package com.tracel.storage.ports

import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.job.RollbackJobRepository as RollbackJobRepositoryPort
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.destinationFor
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records

/** A rollback plan, stored one step per record under `rbStep | job | index`. */
class RollbackJobRepository(private val storage: TracelStorage) : RollbackJobRepositoryPort {
    private companion object {
        val EMPTY = ByteArray(0)

        const val STEPS_PER_RECORD = 512
        const val BYTES_PER_RECORD = 1 shl 16

        inline fun runs(parts: List<ByteArray>, write: (Int, ByteArray) -> Unit) {
            var index = 0
            var from = 0
            while (from < parts.size) {
                var until = from
                var bytes = 5
                // Always takes at least one, so a single oversized part still gets written
                while (until < parts.size && until - from < STEPS_PER_RECORD && bytes < BYTES_PER_RECORD) {
                    bytes += 4 + parts[until].size
                    until++
                }
                write(index++, Records.packed(parts.subList(from, until)))
                from = until
            }
        }
    }

    override suspend fun save(record: RollbackJobRecord) {
        storage.write {
            val target = record.target
            val uniformHolder = when (target) {
                is RollbackTarget.Uniform -> storage.interning.internHolder(this, target.holder)
                is RollbackTarget.PerRoot -> 0
            }
            put(
                Keys.rbJob(record.id.raw),
                Records.rbJob(uniformHolder, record.plan.steps.size, record.create.size, record.destroy.size),
            )

            if (target is RollbackTarget.PerRoot) {
                // Written per (!) leaf lot, already resolved through the plan's rootOf, rather than
                // per root. rootOf is derived at planning time and is not on disk, so a record
                // read back after a restart cannot resolve a leaf to its root — and every lot the
                // plan actually steps on is a leaf. Storing the answer instead of the question
                // is what makes undoing a split rollback possible at all.
                for (lot in record.plan.rootOf.keys + target.byRoot.keys) {
                    val holder = runCatching { target.destinationFor(record.plan, lot) }.getOrNull() ?: continue
                    put(
                        Keys.rbTarget(record.id.raw, lot.raw),
                        Records.int(storage.interning.internHolder(this, holder)),
                    )
                }
            }
            // The undo stack, so nobody ever has to read a job number off a chat line
            put(Keys.rbRecent(record.id.raw), EMPTY)

            runs(record.plan.steps.map { step -> Records.step(step) { holder -> storage.interning.internHolder(this, holder) } }) { index, packed ->
                put(Keys.rbStep(record.id.raw, index), packed)
            }

            // Create first, destroy after, one contiguous run — the header's counts are the
            // boundary, so reading them back is a single prefix scan and a split.
            val structure = (record.create + record.destroy).map { step ->
                Records.structureStep(
                    step,
                    { world -> storage.interning.internWorld(this, world) },
                    { data -> storage.interning.internBlockData(this, data) },
                    { type -> storage.interning.internEntityType(this, type) },
                )
            }
            runs(structure) { index, packed -> put(Keys.rbStruct(record.id.raw, index), packed) }
        }
    }

    override suspend fun undoable(limit: Int): List<RollbackJobId> = storage.read {
        val out = ArrayList<RollbackJobId>(limit)
        // Stored inverted, so the newest job is the first key a forward scan reaches
        scan(Keys.rbRecentPrefix()).use { cursor ->
            while (out.size < limit && cursor.next()) {
                out += RollbackJobId(Keys.invert(com.tracel.storage.codec.KeyReader.u64(cursor.key(), 1)))
            }
        }
        out
    }

    override suspend fun isUndoable(id: RollbackJobId): Boolean = storage.read { exists(Keys.rbRecent(id.raw)) }

    override suspend fun markUndone(id: RollbackJobId) {
        storage.write { delete(Keys.rbRecent(id.raw)) }
    }

    override suspend fun find(id: RollbackJobId): RollbackJobRecord? = storage.read {
        val header = get(Keys.rbJob(id.raw)) ?: return@read null
        val uniformHolder = Records.rbJobRestoreTo(header)
        val target = if (uniformHolder != 0) {
            RollbackTarget.Uniform(storage.interning.resolveHolder(this, uniformHolder))
        } else {
            // Keyed by leaf lot on the way in, so reading it back with an empty rootOf resolves
            // every lot to itself and finds its destination — see save().
            val byLot = mutableMapOf<LotId, com.tracel.model.holder.HolderId>()
            scan(Keys.rbTargetPrefix(id.raw)).use { cursor ->
                while (cursor.next()) {
                    val lot = LotId(com.tracel.storage.codec.KeyReader.u64(cursor.key(), 9))
                    byLot[lot] = storage.interning.resolveHolder(this, Records.asInt(cursor.value()))
                }
            }
            RollbackTarget.PerRoot(byLot)
        }
        val steps = ArrayList<RollbackStep>(Records.rbJobStepCount(header))
        scan(Keys.rbStepPrefix(id.raw)).use { cursor ->
            while (cursor.next()) {
                Records.forEachPacked(cursor.value()) { part ->
                    steps += Records.decodeStep(part) { holderId -> storage.interning.resolveHolder(this, holderId) }
                }
            }
        }
        val structure = ArrayList<StructureStep>(Records.rbJobCreateCount(header) + Records.rbJobDestroyCount(header))
        scan(Keys.rbStructPrefix(id.raw)).use { cursor ->
            while (cursor.next()) {
                Records.forEachPacked(cursor.value()) { part ->
                    structure += Records.decodeStructureStep(
                        part,
                        { worldId -> storage.interning.resolveWorld(this, worldId) },
                        { dataId -> storage.interning.resolveBlockData(this, dataId) },
                        { typeId -> storage.interning.resolveEntityType(this, typeId) },
                    )
                }
            }
        }
        val createCount = Records.rbJobCreateCount(header)

        // rootOf is not persisted: a Uniform target never consults it, and a PerRoot target's
        // map is already keyed by the roots the plan was built from.
        RollbackJobRecord(
            id,
            RollbackPlan(steps),
            target,
            structure.take(createCount),
            structure.drop(createCount),
        )
    }
}
