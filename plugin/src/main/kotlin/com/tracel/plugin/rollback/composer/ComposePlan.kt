package com.tracel.plugin.rollback.composer

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.rollback.structure.CompositeRollbackPlan
import com.tracel.engine.rollback.structure.StructurePlanner
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.world.WorldChange
import com.tracel.plugin.listener.support.entity.LiveProjectile
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.survey.*

/**
 * Compose a plan.
 *
 * Newest-first linkage, older overwrite: earliest in-window source is home.
 */
internal suspend fun RollbackComposer.planRollback(
    filter: LookupFilter,
    structure: Boolean,
    material: Boolean,
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
        services.worldLog.queryTogether(
            services.log,
            wide,
            includeWorld = structure,
            structureEnds = structure,
        )
    }

    // Partner cells: double chest / bed / door / piston, other shit are two coords; "scope:" can land between
    // them and plan left-half only. Same filter, one extra cell.
    val partnered = if (!structure || changes.isEmpty()) changes else {
        withStructuralPartners(changes, wide)
    }

    // Entity trails (snow golem layers outside the "scope:")
    val paired = if (!structure || partnered.isEmpty()) partnered else {
        withTrails(partnered, wide)
    }

    // Structure first, then material
    val (create, destroy) = if (!structure) {
        emptyList<StructureStep>() to emptyList()
    } else {
        StructurePlanner().plan(paired)
    }
    val keepCargoOn = create.mapNotNullTo(HashSet()) { (it as? StructureStep.SpawnEntity)?.entity }
    val covered = if (!structure) null else placedCovered(create + destroy) + LiveProjectile.holders()
    val materials = if (!material) NO_MATERIAL else planMaterial(txns, keepCargoOn, structure, covered)
    val vanishedCells = if (paired.isEmpty()) emptySet() else StructurePlanner().cellsAirToAir(paired)
    val bornAndGone = if (paired.isEmpty()) emptySet() else StructurePlanner().entitiesBornAndGone(paired)
    val target = materials.target.awayFromAirToAir(vanishedCells).awayFromEntitiesGone(bornAndGone)
    return Planned(
        CompositeRollbackPlan(create, materials.plan, destroy),
        target,
        materials.roots,
        materials.vanished,
        materials.witness,
        flushed,
        materials.placedAndUnreachable,
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
