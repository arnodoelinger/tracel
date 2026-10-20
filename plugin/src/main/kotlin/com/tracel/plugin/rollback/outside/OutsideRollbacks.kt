package com.tracel.plugin.rollback.outside

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.rollback.outside.OutsideStep
import com.tracel.model.event.EventKind
import com.tracel.model.holder.HolderId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.api.addon.EventText
import com.tracel.plugin.api.addon.Extensions
import com.tracel.plugin.api.addon.withItemLens
import com.tracel.plugin.rollback.RollbackGenius
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.services.TracelServices

/**
 * [inner], with addon records that live outside the world taken back alongside it.
 *
 * Plans pick up the reversible addon events the filter reaches; their sequence numbers join what the job takes, so
 * the job's rolled-back marks cover them and its undo clears them. Telling the addons is left to the reconciler,
 * after the world part is written: if `Tracel` dies in between, it tells them on the way back up.
 *
 * Also where an item name a lens knows becomes the item keys it stands for, so every rollback, command or API,
 * sees custom items the same way.
 */
internal class OutsideRollbacks(
    private val services: TracelServices,
    private val inner: RollbackGenius,
    private val extensions: Extensions,
) : RollbackGenius by inner {
    private val reconciler = OutsideReconciler(services.rolledBack, services.outside, services.scope) { kind ->
        extensions.reversibles[kind]
    }

    override suspend fun plan(filter: LookupFilter, structure: Boolean, material: Boolean): Planned {
        val lensed = services.withItemLens(filter)
        val planned = inner.plan(lensed, structure, material)
        if (!material || !lensed.reachesEvents()) return planned
        val steps = reversibleIn(lensed)
        if (steps.isEmpty()) return planned
        return planned.copy(taken = planned.taken + LongArray(steps.size) { steps[it].seq }, outside = steps)
    }

    override suspend fun apply(planned: Planned, strict: Boolean): RollbackResult {
        if (planned.outside.isEmpty()) return inner.apply(planned, strict)
        orphanOf(planned.outside)?.let { return it }
        services.outside.stage(planned.outside)
        val result = inner.apply(planned, strict)
        if (result is RollbackResult.Done) {
            val seqs = LongArray(planned.outside.size) { planned.outside[it].seq }
            services.outside.bind(result.job.raw, seqs)
            reconciler.reconcile(services.outside.ofJob(result.job.raw))
        }
        return result
    }

    override suspend fun undo(job: RollbackJobId): UndoResult {
        val steps = services.outside.ofJob(job.raw)
        orphanOf(steps)?.let { return it }
        val result = inner.undo(job)
        if (result is UndoResult.Done && steps.isNotEmpty()) reconciler.reconcile(services.outside.ofJob(job.raw))
        return result
    }

    private fun orphanOf(steps: List<OutsideStep>): Unreachable? {
        val orphan = steps.firstOrNull { it.kind !in extensions.reversibles } ?: return null
        return Unreachable(HolderId.Custom(orphan.kind, ""), "the addon that takes back ${orphan.kind} is not installed.")
    }

    private suspend fun reversibleIn(filter: LookupFilter): List<OutsideStep> {
        val wide = filter.copy(limit = Int.MAX_VALUE, offset = 0)
        val found = services.reading { services.events.query(wide, setOf(EventKind.CUSTOM)) }.mapNotNull { event ->
            val raw = event.text ?: return@mapNotNull null
            val text = raw.substringAfter(' ', "")
            if (!EventText.isReversible(text)) return@mapNotNull null
            OutsideStep(event.seq.raw, raw.substringBefore(' '), text)
        }
        if (found.isEmpty()) return found
        val held = services.rolledBack.of(found.map { it.seq })
        return found.filter { it.seq !in held }
    }

    private fun LookupFilter.reachesEvents(): Boolean =
        actions.isEmpty() && material == null && itemKeys.isEmpty() && blockMaterials.isEmpty() && entity == null
}
