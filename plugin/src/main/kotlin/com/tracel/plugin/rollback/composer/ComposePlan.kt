package com.tracel.plugin.rollback.composer

import com.tracel.plugin.listener.support.LiveProjectiles
import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.rollback.structure.CompositeRollbackPlan
import com.tracel.engine.rollback.structure.StructurePlanner
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.world.WorldChange
import com.tracel.plugin.rollback.survey.NO_MATERIAL
import com.tracel.plugin.rollback.survey.awayFromAirToAir
import com.tracel.plugin.rollback.survey.planMaterial
import com.tracel.plugin.rollback.survey.withStructuralPartners
import com.tracel.plugin.rollback.survey.withTrails
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.trace.RollbackTrace
import com.tracel.storage.ports.log.QueryProbe

/**
 * Compose a plan.
 *
 * Newest-first linkage, older overwrite: earliest in-window source is home.
 */
internal suspend fun RollbackComposer.planRollback(
    filter: LookupFilter,
    structure: Boolean,
    material: Boolean,
    trace: RollbackTrace, // TODO: remove me
): Planned {
    val wide = filter.copy(
        limit = Int.MAX_VALUE,
        offset = 0,
        excludedCauses = filter.excludedCauses + CauseKind.ROLLBACK + CauseKind.INVOLUTION,
    )

    val flushed = services.flushCapture()

    val (changes, txns) = if (!structure && !material) {
        emptyList<WorldChange>() to emptyList()
    } else {
        QueryProbe.reset()
        trace.span("world log query") {
            services.worldLog.queryTogether(
                services.log,
                wide,
                includeWorld = structure,
                structureEnds = structure,
            )
        }.also {
            val probe = QueryProbe.read()
            fun capped(value: Long) = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            trace.note("index rows", capped(probe.indexRows))
            trace.note("index kept", capped(probe.kept))
            trace.note("records opened", capped(probe.records))
            trace.note("cursors", capped(probe.cursors))
            trace.note("scanned", capped(probe.scanned))
            trace.note("point-got", capped(probe.pointGot))
            trace.note("inlined", capped(probe.inlined))
            trace.note("delta rows", capped(probe.deltaRows))
            trace.note("delta blocks", capped(probe.deltaBlocks))
            trace.note("txns", it.second.size)
            for ((phase, nanos) in probe.nanos) trace.addNanos("world log query / $phase", nanos)
        }
    }
    if (structure) trace.note("changes", changes.size)

    // Partner cells: double chest / bed / door / piston, other shit are two coords; "scope:" can land between
    // them and plan left-half only. Same filter, one extra cell.
    val partnered = if (!structure || changes.isEmpty()) changes else {
        trace.span("structural partners") { withStructuralPartners(changes, wide) }
    }

    // Entity trails (snow golem layers outside the "scope:")
    val paired = if (!structure || partnered.isEmpty()) partnered else {
        trace.span("entity trails") { withTrails(partnered, wide) }
    }

    // Structure first, then material
    val (create, destroy) = if (!structure) {
        emptyList<StructureStep>() to emptyList()
    } else {
        trace.span("plan structure") { StructurePlanner().plan(paired) }
    }
    val keepCargoOn = create.mapNotNullTo(HashSet()) { (it as? StructureStep.SpawnEntity)?.entity }
    val covered = if (!structure) null else placedCovered(create + destroy) + LiveProjectiles.holders()
    val materials = if (!material) NO_MATERIAL else planMaterial(txns, trace, keepCargoOn, structure, covered)
    val vanishedCells = if (paired.isEmpty()) emptySet() else StructurePlanner().cellsAirToAir(paired)
    val target = materials.target.awayFromAirToAir(vanishedCells)
    return Planned(
        CompositeRollbackPlan(create, materials.plan, destroy),
        target,
        materials.roots,
        materials.vanished,
        materials.witness,
        flushed,
        materials.placedAndUnreachable,
        trace,
        filter.since,
        structure,
        covered,
    )
}

private fun placedCovered(steps: List<StructureStep>): Set<HolderId> {
    val out = HashSet<HolderId>(steps.size)
    for (step in steps) {
        when (step) {
            is StructureStep.SetBlock -> out += HolderId.PlacedBlock(step.at.world, step.at.x, step.at.y, step.at.z)
            is StructureStep.RemoveEntity -> out += HolderId.PlacedEntity(step.entity)
            is StructureStep.SpawnEntity -> out += HolderId.PlacedEntity(step.entity)
        }
    }
    return out
}
