package com.tracel.storage.ports.job

import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.job.SaveHandle
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.destinationFor
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.codec.records.SectionExtras
import com.tracel.storage.util.eachRow
import java.lang.foreign.MemorySegment
import com.tracel.engine.rollback.job.RollbackJobRepository as RollbackJobRepositoryPort

/** A rollback plan, stored one step per record under `rbStep | job | index`. */
class RollbackJobRepository(private val storage: TracelStorage) : RollbackJobRepositoryPort {
    private companion object {
        /** The console's stack. Interned holders start at 1. */
        const val CONSOLE = 0

        /** A job saved before owners were kept: nobody's, so nobody can undo it by accident. */
        const val UNOWNED = -1

        const val STEPS_PER_RECORD = 512
        const val BYTES_PER_RECORD = 1 shl 16

        const val SECTION_FROM = 2

        /** Writes [parts] in runs of at most [STEPS_PER_RECORD] and [BYTES_PER_RECORD], calling [write] for each run. */
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
        finish(begin(record), record.destroy)
    }

    override suspend fun begin(record: RollbackJobRecord): SaveHandle {
        var runsWritten = 0
        storage.write {
            val target = record.target
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

            runs(record.plan.steps.map { step ->
                Records.step(step) { holder ->
                    storage.interning.internHolder(
                        this,
                        holder
                    )
                }
            }) { index, packed ->
                put(Keys.rbStep(record.id.raw, index), packed)
            }

            // Create first, destroy after, one contiguous run — the header's counts are the
            // boundary, so reading them back is a single prefix scan and a split.
            runs(structureRuns(this, record.create)) { index, packed ->
                put(Keys.rbStruct(record.id.raw, index), packed)
                runsWritten = index + 1
            }

            // The ledger already moved: a crash before finish must still leave something to undo,
            // even if it cannot put back what the contested pass had yet to remove.
            putHeader(record, destroyCount = 0)
            put(Keys.rbRecent(record.id.raw), ownerValue(record.by))
            evictPastDepth(ownerValue(record.by))
        }
        return SaveHandle(record, runsWritten)
    }

    override suspend fun finish(handle: SaveHandle, destroy: List<StructureStep>) {
        val record = handle.record
        storage.write {
            if (!exists(Keys.rbJob(record.id.raw))) return@write
            runs(structureRuns(this, destroy)) { index, packed ->
                put(Keys.rbStruct(record.id.raw, handle.fromRun + index), packed)
            }
            putHeader(record, destroyCount = destroy.size)
        }
    }

    override suspend fun undoable(limit: Int): List<RollbackJobId> = storage.read {
        val out = ArrayList<RollbackJobId>(limit)

        scan(Keys.rbRecentPrefix()).use { cursor ->
            while (out.size < limit && cursor.next()) {
                out += RollbackJobId(Keys.invert(KeyReader.u64(cursor.key(), 1)))
            }
        }
        out
    }

    override suspend fun undoableBy(by: HolderId?, limit: Int): List<RollbackJobId> = storage.read {
        val out = ArrayList<RollbackJobId>(limit)
        val owner = if (by == null) CONSOLE else storage.interning.findHolderId(this, by) ?: return@read out
        scan(Keys.rbRecentPrefix()).use { cursor ->
            while (out.size < limit && cursor.next()) {
                if (ownerOf(cursor.value()) != owner) continue
                out += RollbackJobId(Keys.invert(KeyReader.u64(cursor.key(), 1)))
            }
        }
        out
    }

    override suspend fun isUndoable(id: RollbackJobId): Boolean = storage.read { exists(Keys.rbRecent(id.raw)) }

    override suspend fun markUndone(id: RollbackJobId) {
        storage.write { forget(id) }
    }

    override suspend fun find(id: RollbackJobId): RollbackJobRecord? = storage.read {
        val header = get(Keys.rbJob(id.raw)) ?: return@read null
        val uniformHolder = Records.rbJobRestoreTo(header)
        val target = if (uniformHolder != 0) {
            RollbackTarget.Uniform(storage.interning.resolveHolder(this, uniformHolder))
        } else {
            val byLot = mutableMapOf<LotId, HolderId>()
            eachRow(Keys.rbTargetPrefix(id.raw)) { cursor ->
                val lot = LotId(KeyReader.u64(cursor.key(), 9))
                byLot[lot] = storage.interning.resolveHolder(this, Records.asInt(cursor.value()))
            }
            RollbackTarget.PerRoot(byLot)
        }
        val steps = ArrayList<RollbackStep>(Records.rbJobStepCount(header))
        eachRow(Keys.rbStepPrefix(id.raw)) { cursor ->
            Records.forEachPacked(cursor.value()) { part ->
                steps += Records.decodeStep(part) { holderId -> storage.interning.resolveHolder(this, holderId) }
            }
        }
        val structure = ArrayList<StructureStep>(Records.rbJobCreateCount(header) + Records.rbJobDestroyCount(header))
        eachRow(Keys.rbStructPrefix(id.raw)) { cursor ->
            Records.forEachPacked(cursor.value()) { part ->
                Records.decodeStructureInto(
                    part,
                    { worldId -> storage.interning.resolveWorld(this, worldId) },
                    { dataId -> storage.interning.resolveBlockData(this, dataId) },
                    { typeId -> storage.interning.resolveEntityType(this, typeId) },
                    structure,
                )
            }
        }
        val createCount = Records.rbJobCreateCount(header)
        val by = get(Keys.rbRecent(id.raw))?.let(::ownerOf)?.takeIf { it > CONSOLE }?.let { storage.interning.resolveHolder(this, it) }

        RollbackJobRecord(
            id,
            RollbackPlan(steps),
            target,
            structure.take(createCount),
            structure.drop(createCount),
            Records.rbJobTargetTime(header),
            Records.rbJobExecutedAt(header),
            by,
        )
    }

    private fun StorageUnit.putHeader(record: RollbackJobRecord, destroyCount: Int) {
        val uniformHolder = when (val target = record.target) {
            is RollbackTarget.Uniform -> storage.interning.internHolder(this, target.holder)
            is RollbackTarget.PerRoot -> 0
        }
        put(
            Keys.rbJob(record.id.raw),
            Records.rbJob(
                uniformHolder, record.plan.steps.size, record.create.size, destroyCount,
                record.targetTimeMillis ?: 0L, record.targetTimeMillis != null, record.executedAtMillis,
            ),
        )
    }

    private fun structureRuns(unit: StorageUnit, steps: List<StructureStep>): List<ByteArray> {
        val worldOf = { world: WorldId -> storage.interning.internWorld(unit, world) }
        val dataOf = { data: BlockDataKey -> storage.interning.internBlockData(unit, data) }
        val typeOf = { type: EntityTypeKey -> storage.interning.internEntityType(unit, type) }

        val bySection = LinkedHashMap<Long, MutableList<StructureStep.SetBlock>>()
        val loose = ArrayList<StructureStep>()
        for (step in steps) {
            if (step is StructureStep.SetBlock) {
                bySection.getOrPut(sectionOf(step.at)) { ArrayList() } += step
            } else {
                loose += step
            }
        }

        val out = ArrayList<ByteArray>(steps.size)
        for (group in bySection.values) {
            if (group.size < SECTION_FROM) {
                for (step in group) out += Records.structureStep(step, worldOf, dataOf, typeOf)
                continue
            }
            val ordered = group.sortedBy { Records.packSectionPosition(it.at.x, it.at.y, it.at.z) }
            val count = ordered.size
            val positions = IntArray(count)
            val target = IntArray(count)
            val expected = IntArray(count)
            val extras = ArrayList<SectionExtras>()
            for (i in 0 until count) {
                val step = ordered[i]
                positions[i] = Records.packSectionPosition(step.at.x, step.at.y, step.at.z)
                target[i] = dataOf(step.target.data)
                expected[i] = dataOf(step.expected.data)
                if (step.target.extras != null || step.expected.extras != null) {
                    extras += SectionExtras(
                        i,
                        Records.blockExtras(step.target.extras),
                        Records.blockExtras(step.expected.extras),
                    )
                }
            }
            val first = ordered.first().at
            out += Records.structureSection(
                worldOf(first.world), first.x shr 4, first.y shr 4, first.z shr 4,
                positions, count, target, expected, extras,
            )
        }
        for (step in loose) out += Records.structureStep(step, worldOf, dataOf, typeOf)
        return out
    }

    private fun sectionOf(at: BlockPos): Long =
        (at.world.uuid.leastSignificantBits shl 1) xor
                ((at.x shr 4).toLong() and 0x1FFFFF shl 42) xor
                ((at.z shr 4).toLong() and 0x1FFFFF shl 21) xor
                ((at.y shr 4).toLong() and 0x1FFFFF)

    private fun StorageUnit.ownerValue(by: HolderId?): ByteArray =
        Records.int(if (by == null) CONSOLE else storage.interning.internHolder(this, by))

    private fun ownerOf(value: MemorySegment): Int = if (value.byteSize() < Int.SIZE_BYTES) UNOWNED else Records.asInt(value)

    private fun StorageUnit.forget(id: RollbackJobId) {
        val doomed = ArrayList<ByteArray>()
        val prefixes = listOf(
            Keys.rbStepPrefix(id.raw), Keys.rbStructPrefix(id.raw), Keys.rbTargetPrefix(id.raw),
            Keys.appliedPrefix(Keys.PROGRESS_ROLLBACK, id.raw), Keys.appliedPrefix(Keys.PROGRESS_INVOLUTION, id.raw),
        )
        for (prefix in prefixes) {
            eachRow(prefix) { cursor -> doomed += cursor.key() }
        }
        doomed.forEach(::delete)
        delete(Keys.rbJob(id.raw))
        delete(Keys.rbRecent(id.raw))
    }

    private fun StorageUnit.evictPastDepth(owner: ByteArray) {
        val doomed = ArrayList<RollbackJobId>()
        val mine = Records.asInt(MemorySegment.ofArray(owner))
        var seen = 0
        eachRow(Keys.rbRecentPrefix()) { cursor ->
            if (ownerOf(cursor.value()) != mine) return@eachRow
            seen++
            if (seen > RollbackJobRepositoryPort.UNDO_DEPTH) {
                doomed += RollbackJobId(Keys.invert(cursor.keyU64(1)))
            }
        }
        for (id in doomed) forget(id)
    }
}
