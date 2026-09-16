package com.tracel.plugin.rollback.composer

import com.tracel.engine.rollback.job.Reservation
import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.job.RollbackOutcome
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.census.EntityCensus
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.CargoPolicy
import com.tracel.plugin.rollback.structure.StructurePass
import com.tracel.plugin.rollback.structure.StructurePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

/** What the first wait left behind. */
internal class FirstWait(
    val outcome: RollbackOutcome?,
    val created: StructureReport,
    val destroyedPrompt: StructureReport,
    val census: Deferred<EntityCensus>,
    val ledgerFailure: Throwable?,
)

/** First wait: ledger, un-contested creates, and prompt destroys run together. */
internal suspend fun RollbackComposer.ledgerAndStructure(
    planned: Planned,
    strict: Boolean,
    deltas: Map<HolderId, Map<ItemKey, Long>>,
    layout: ApplyLayout,
    reservation: Reservation?,
): FirstWait {
    var outcome: RollbackOutcome? = null
    val created: StructureReport
    val destroyedPrompt: StructureReport
    val census: Deferred<EntityCensus>
    var ledgerFailure: Throwable? = null
    coroutineScope {
        val ledger = if (reservation !is Reservation.Granted) null else async {
            // A thrown async cancels the scope before inverse-restore can run
            try {
                Result.success(
                    planned.trace.span("ledger") {
                        services.rollback.apply(reservation, planned.target, recordsOwnJob = false)
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }
        val blocks = async {
            planned.trace.span("restore blocks") {
                structureHalf.restore(
                    layout.createNow,
                    // Forward: un-ledgered cargo in a container is real; refuse, and don't dump
                    StructurePass(force = !strict, phase = StructurePhase.RESTORE, drain = true, dumpHeldCargo = false),
                    CargoPolicy(keepCargoFor = layout.keepCargoFor),
                    trace = planned.trace,
                )
            }
        }

        // locate() skips respawning UUIDs; census-vs-spawn would mark the frame missing
        census = async { materialHalf.locate(deltas, layout.respawning) }
        val gone = async {
            planned.trace.span("remove blocks") {
                structureHalf.restore(
                    layout.prompt,
                    StructurePass(force = !strict, phase = StructurePhase.REMOVE, drain = true, dumpHeldCargo = false),
                    CargoPolicy(ledgerCargoFor = layout.ledgerCargoFor, ledgerHeldBy = layout.ledgerHeldBy),
                    trace = planned.trace,
                )
            }
        }
        created = blocks.await()
        destroyedPrompt = gone.await()
        ledger?.await()?.fold({ outcome = it }, { ledgerFailure = it })
    }
    return FirstWait(outcome, created, destroyedPrompt, census, ledgerFailure)
}

/** What the material wait left behind: the contested destroy and what moved. */
internal class MaterialWait(val extra: StructureReport, val material: RestorationReport)

/** Items after the ledger, contested destroy after the cargo it waits on, the job record last. */
internal suspend fun RollbackComposer.materialAndContested(
    planned: Planned,
    strict: Boolean,
    deltas: Map<HolderId, Map<ItemKey, Long>>,
    layout: ApplyLayout,
    job: RollbackJobId,
    startedAtMillis: Long,
    first: FirstWait,
): MaterialWait {
    val composite = planned.composite

    var material = RestorationReport(emptyMap())
    val applied = first.outcome as? RollbackOutcome.Applied
    if (applied != null) planned.trace.note("ledger steps", applied.plan.steps.size)

    // Material after ledger
    val groundGoing = deltas.keys.filterIsInstance<HolderId.ItemEntity>().mapTo(HashSet()) { it.uuid }
    val early = layout.entityDestroy.none { (it as StructureStep.RemoveEntity).entity in groundGoing }

    val contestedReport = coroutineScope {
        val settled = CompletableDeferred<Unit>()
        val items = if (first.outcome !is RollbackOutcome.Applied) null else async {
            planned.trace.span("move items") {
                materialHalf.restore(
                    deltas, job,
                    knownGone = planned.vanished,
                    trace = planned.trace,
                    census = first.census.await(),
                    settled = if (early) settled else null,
                    asOf = planned.targetTimeMillis,
                )
            }.also {
                val asOf = planned.targetTimeMillis
                if (asOf != null) planned.trace.span("rewear tools") { materialHalf.rewear(composite.material, planned.target, job, asOf) }
            }
        }
        val removing = if (layout.deferred.isEmpty()) null else async {
            if (early && items != null) settled.await() else items?.await()
            planned.trace.span("remove contested") {
                structureHalf.restore(
                    layout.deferred,
                    StructurePass(
                        force = !strict,
                        phase = StructurePhase.CONTESTED,
                        drain = true,
                        dumpHeldCargo = false
                    ),
                    CargoPolicy(ledgerCargoFor = layout.ledgerCargoFor, ledgerHeldBy = layout.ledgerHeldBy),
                    trace = planned.trace,
                )
            }
        }
        material = items?.await() ?: material

        // begin() under this wait; finish() after destroy; undo stack only once the job is whole
        val saving = async {
            planned.trace.span("save job") {
                services.jobs.begin(
                    RollbackJobRecord(
                        job, composite.material, planned.target, first.created.applied,
                        targetTimeMillis = planned.targetTimeMillis, executedAtMillis = startedAtMillis,
                    )
                )
            }
        }
        val extra = removing?.await() ?: StructureReport.EMPTY
        val allDestroyed = first.destroyedPrompt + extra
        planned.trace.span("save job") {
            services.jobs.finish(saving.await(), allDestroyed.applied)
        }
        extra
    }
    return MaterialWait(contestedReport, material)
}
