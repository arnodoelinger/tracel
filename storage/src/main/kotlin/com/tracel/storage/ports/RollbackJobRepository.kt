package com.tracel.storage.ports

import com.tracel.engine.rollback.RollbackJobRecord
import com.tracel.engine.rollback.RollbackJobRepository as RollbackJobRepositoryPort
import com.tracel.engine.rollback.RollbackPlan
import com.tracel.engine.rollback.RollbackStep
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records

/** A rollback plan, stored one step per record under `rbStep | job | index`. */
class RollbackJobRepository(private val storage: TracelStorage) : RollbackJobRepositoryPort {
    override suspend fun save(record: RollbackJobRecord) {
        storage.write {
            put(
                Keys.rbJob(record.id.raw),
                Records.rbJob(storage.interning.internHolder(this, record.restoreTo), record.plan.steps.size),
            )
            record.plan.steps.forEachIndexed { index, step ->
                put(
                    Keys.rbStep(record.id.raw, index),
                    Records.step(step) { holder -> storage.interning.internHolder(this, holder) },
                )
            }
        }
    }

    override suspend fun find(id: RollbackJobId): RollbackJobRecord? = storage.read {
        val header = get(Keys.rbJob(id.raw)) ?: return@read null
        val restoreTo = storage.interning.resolveHolder(this, Records.rbJobRestoreTo(header))
        val steps = ArrayList<RollbackStep>(Records.rbJobStepCount(header))
        scan(Keys.rbStepPrefix(id.raw)).use { cursor ->
            while (cursor.next()) {
                steps += Records.decodeStep(cursor.value()) { holderId ->
                    storage.interning.resolveHolder(this, holderId)
                }
            }
        }
        RollbackJobRecord(id, RollbackPlan(steps), restoreTo)
    }
}
