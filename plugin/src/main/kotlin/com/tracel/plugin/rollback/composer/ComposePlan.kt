package com.tracel.plugin.rollback.composer

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.rollback.structure.CompositeRollbackPlan
import com.tracel.engine.rollback.structure.StructurePlanner
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.WorldChange
import com.tracel.plugin.listener.support.entity.LiveProjectile
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.survey.*
import com.tracel.plugin.util.chunkKey
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

internal const val FULL_FLUSH_SECONDS = 60

private const val FLUSH_ROUND_PAUSE_MILLIS = 50L

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

    val flushed = flushedFully()

    val (matchedChanges, matchedTxns) = if (!structure && !material) {
        emptyList<WorldChange>() to emptyList()
    } else {
        services.worldLog.queryTogether(
            services.log,
            wide,
            includeWorld = structure,
            structureEnds = structure,
        )
    }

    // Imported history is read-only
    val importedBelow = services.foreign.importedBelow()

    // Nothing imported is the usual case, and then there is nothing to cut out of a few million rows
    val (changes, txns, imported) = if (importedBelow <= 0L) Triple(matchedChanges, matchedTxns, 0) else {
        val keptChanges = matchedChanges.filter { it.seq.raw >= importedBelow }
        val keptTxns = matchedTxns.filter { it.seq.raw >= importedBelow }
        Triple(
            keptChanges, keptTxns,
            (matchedChanges.size - keptChanges.size) + (matchedTxns.size - keptTxns.size),
        )
    }

    // Partner cells: double chest / bed / door / piston, other shit are two coords; "scope:" can land between
    // them and plan left-half only. Same filter, one extra cell.
    val partnered = if (!structure || changes.isEmpty()) changes else {
        withStructuralPartners(changes, wide)
    }

    // Entity trails (snow golem layers outside the "scope:").
    // A click left the block as it was; planning it would put that block back over whatever stands there now.
    val paired = (if (!structure || partnered.isEmpty()) partnered else withTrails(partnered, wide))
        .filterNot { it.action == ActionKind.BLOCK_CLICK || it.seq.raw < importedBelow }

    // Structure first, then material
    val outcome = if (!structure) null else StructurePlanner().planAll(paired)
    val create = outcome?.create?.inPlaceOrder() ?: emptyList()
    val destroy = outcome?.destroy?.inPlaceOrder() ?: emptyList()
    val keepCargoOn = create.mapNotNullTo(HashSet()) { (it as? StructureStep.SpawnEntity)?.entity }
    val covered = if (!structure) null else placedCovered(create + destroy) + LiveProjectile.holders()
    val materials = if (!material) NO_MATERIAL else planMaterial(txns, keepCargoOn, structure, covered)
    val vanishedCells = outcome?.airToAir ?: emptySet()
    val bornAndGone = outcome?.bornAndGone ?: emptySet()
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
        imported = imported,
        asItStood = filter.region?.takeIf { structure && imported == 0 && filter.leavesNothingOut() },
    )
}

/** Block steps in the order they sit in the world. */
internal fun List<StructureStep>.inPlaceOrder(): List<StructureStep> {
    if (size < 2) return this
    val byWorld = LinkedHashMap<Any, HashMap<Long, ArrayList<StructureStep.SetBlock>>>()
    val others = ArrayList<StructureStep>()
    for (step in this) {
        if (step !is StructureStep.SetBlock) {
            others += step
            continue
        }
        byWorld.getOrPut(step.at.world) { HashMap() }.getOrPut(chunkKey(step.at.x, step.at.z)) { ArrayList() } += step
    }
    val out = ArrayList<StructureStep>(size)
    for (chunks in byWorld.values) {
        val keys = chunks.keys.toLongArray().also { it.sort() }
        for (key in keys) {
            val inChunk = chunks.getValue(key)
            inChunk.sortBy { (it.at.y shl 8) or ((it.at.z and 15) shl 4) or (it.at.x and 15) }
            out += inChunk
        }
    }
    out += others
    return out
}

private fun LookupFilter.leavesNothingOut(): Boolean =
    holders.isEmpty() && excludedHolders.isEmpty() && material == null && blockMaterials.isEmpty() &&
            causes.isEmpty() && worldCauses.isNullOrEmpty() && excludedCauses.isEmpty() && actions.isEmpty() &&
            until == null && (world == null || world == region?.world)

private suspend fun RollbackComposer.flushedFully(): Boolean {
    val deadline = System.currentTimeMillis() + FULL_FLUSH_SECONDS * 1_000L
    while (!services.flushCapture()) {
        if (System.currentTimeMillis() >= deadline) return false
        delay(FLUSH_ROUND_PAUSE_MILLIS.milliseconds)
    }
    return true
}

private fun placedCovered(steps: List<StructureStep>): Set<HolderId> {
    val out = HashSet<HolderId>(steps.size)
    for (step in steps) {
        out += when (step) {
            is StructureStep.SetBlock -> HolderId.PlacedBlock(step.at.world, step.at.x, step.at.y, step.at.z)
            is StructureStep.RemoveEntity -> HolderId.PlacedEntity(step.entity)
            is StructureStep.SpawnEntity -> HolderId.PlacedEntity(step.entity)
        }
    }
    return out
}
