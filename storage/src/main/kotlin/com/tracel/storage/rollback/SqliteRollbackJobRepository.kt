package com.tracel.storage.rollback

import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.engine.rollback.LotContribution
import com.tracel.engine.rollback.RollbackJobRecord
import com.tracel.engine.rollback.RollbackJobRepository
import com.tracel.engine.rollback.RollbackPlan
import com.tracel.engine.rollback.RollbackStep
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.storage.intern.Interning
import com.tracel.storage.schema.RollbackJobsTable
import com.tracel.storage.schema.RollbackStepInputsTable
import com.tracel.storage.schema.RollbackStepsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * `SQLite`-backed [RollbackJobRepository].
 *
 * Every [RollbackStep] variant is spread across [RollbackStepsTable]'s nullable, variant-specific
 * columns rather than a serialized blob.
 */
class SqliteRollbackJobRepository(private val db: Database) : RollbackJobRepository {
    private val writer = SingleWriterGuard()

    override fun save(record: RollbackJobRecord) {
        writer.checkIn()
        transaction(db) {
            RollbackJobsTable.insert {
                it[jobId] = record.id.raw
                it[restoreToHolderId] = Interning.internHolder(record.restoreTo)
            }

            record.plan.steps.forEachIndexed { idx, step ->
                RollbackStepsTable.insert { row ->
                    row[jobId] = record.id.raw
                    row[RollbackStepsTable.idx] = idx
                    when (step) {
                        is RollbackStep.Take -> {
                            row[kind] = "TAKE"
                            row[lotId] = step.lotId.raw
                            row[quantity] = step.quantity.raw
                            row[holderId] = Interning.internHolder(step.holder)
                        }

                        is RollbackStep.Mint -> {
                            row[kind] = "MINT"
                            row[lotId] = step.lotId.raw
                            row[quantity] = step.quantity.raw
                            row[reason] = step.reason.name
                        }

                        is RollbackStep.Debt -> {
                            row[kind] = "DEBT"
                            row[lotId] = step.lotId.raw
                            row[quantity] = step.quantity.raw
                            row[playerUuid] = step.player.toString()
                        }

                        is RollbackStep.Unmake -> {
                            row[kind] = "UNMAKE"
                            row[outputLot] = step.outputLot.raw
                            row[holderId] = Interning.internHolder(step.holder)
                            row[craftedBy] = step.craftedBy.raw
                        }
                    }
                }

                if (step is RollbackStep.Unmake) {
                    step.inputs.forEachIndexed { inputIdx, contribution ->
                        RollbackStepInputsTable.insert { row ->
                            row[jobId] = record.id.raw
                            row[stepIdx] = idx
                            row[RollbackStepInputsTable.inputIdx] = inputIdx
                            row[lotId] = contribution.lotId.raw
                            row[quantity] = contribution.quantity.raw
                        }
                    }
                }
            }
        }
    }

    override fun find(id: RollbackJobId): RollbackJobRecord? = transaction(db) {
        val jobRow = RollbackJobsTable.selectAll().where { RollbackJobsTable.jobId eq id.raw }.singleOrNull()
            ?: return@transaction null
        val restoreTo = Interning.resolveHolder(jobRow[RollbackJobsTable.restoreToHolderId])

        val steps = RollbackStepsTable.selectAll()
            .where { RollbackStepsTable.jobId eq id.raw }
            .orderBy(RollbackStepsTable.idx)
            .map { it.toStep(id) }

        RollbackJobRecord(id, RollbackPlan(steps), restoreTo)
    }

    private fun ResultRow.toStep(jobId: RollbackJobId): RollbackStep {
        val idx = this[RollbackStepsTable.idx]
        return when (val kind = this[RollbackStepsTable.kind]) {
            "TAKE" -> RollbackStep.Take(
                LotId(this[RollbackStepsTable.lotId] ?: error("TAKE step $jobId/$idx missing lot_id")),
                Quantity(this[RollbackStepsTable.quantity] ?: error("TAKE step $jobId/$idx missing quantity")),
                Interning.resolveHolder(this[RollbackStepsTable.holderId] ?: error("TAKE step $jobId/$idx missing holder_id")),
            )

            "MINT" -> RollbackStep.Mint(
                LotId(this[RollbackStepsTable.lotId] ?: error("MINT step $jobId/$idx missing lot_id")),
                Quantity(this[RollbackStepsTable.quantity] ?: error("MINT step $jobId/$idx missing quantity")),
                SinkKind.valueOf(this[RollbackStepsTable.reason] ?: error("MINT step $jobId/$idx missing reason")),
            )

            "DEBT" -> RollbackStep.Debt(
                LotId(this[RollbackStepsTable.lotId] ?: error("DEBT step $jobId/$idx missing lot_id")),
                Quantity(this[RollbackStepsTable.quantity] ?: error("DEBT step $jobId/$idx missing quantity")),
                UUID.fromString(this[RollbackStepsTable.playerUuid] ?: error("DEBT step $jobId/$idx missing player_uuid")),
            )

            "UNMAKE" -> {
                val inputs = RollbackStepInputsTable.selectAll()
                    .where { (RollbackStepInputsTable.jobId eq jobId.raw) and (RollbackStepInputsTable.stepIdx eq idx) }
                    .orderBy(RollbackStepInputsTable.inputIdx)
                    .map { row -> LotContribution(LotId(row[RollbackStepInputsTable.lotId]), Quantity(row[RollbackStepInputsTable.quantity])) }

                RollbackStep.Unmake(
                    LotId(this[RollbackStepsTable.outputLot] ?: error("UNMAKE step $jobId/$idx missing output_lot")),
                    inputs,
                    TxnId(this[RollbackStepsTable.craftedBy] ?: error("UNMAKE step $jobId/$idx missing crafted_by")),
                    Interning.resolveHolder(this[RollbackStepsTable.holderId] ?: error("UNMAKE step $jobId/$idx missing holder_id")),
                )
            }

            else -> error("unrecognized rollback step kind: $kind")
        }
    }
}
