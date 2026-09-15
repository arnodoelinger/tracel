package com.tracel.plugin.rollback.composer

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.util.blockPos
import java.util.UUID

/** Which steps go in which wait, and whose cargo the ledger owns. Worked out once, before anything moves. */
internal class ApplyLayout(
    val respawning: Set<UUID>,
    val keepCargoFor: Set<UUID>,
    val ledgerCargoFor: Set<UUID>,
    val ledgerHeldBy: Set<UUID>,
    val entityDestroy: List<StructureStep>,
    val prompt: List<StructureStep>,
    val createNow: List<StructureStep>,
    val deferred: List<StructureStep>,
)

/** Sorts steps into their wait and cargo bucket once, so the waits themselves stay dumb. */
internal suspend fun RollbackComposer.layoutOf(
    planned: Planned,
    deltas: Map<HolderId, Map<ItemKey, Long>>,
): ApplyLayout {
    val composite = planned.composite

    // Contested cells wait on cargo. Other destroys must not serialize on a Folia hop
    val contested = deltas.keys.mapNotNullTo(HashSet<BlockPos>()) { it.blockPos() }
    val respawning = composite.create.asSequence()
        .filterIsInstance<StructureStep.SpawnEntity>()
        .mapTo(HashSet()) { it.entity }

    // Ledger-filled hulls: strip snapshot or the sword dupes. Unbooked (creative / pre-plugin): keep cargo
    val fedByLedger = filledByLedger(deltas)
    val keepCargoFor = respawning - fedByLedger

    // Removals: refuse if this job's take failed
    val ledgerCargoFor = emptiedByLedger(deltas)

    // Booked cargo the plan missed
    val ledgerHeldBy = planned.trace.span("hull cargo") {
        services.heldByLedger(
            composite.destroy.asSequence()
                .filterIsInstance<StructureStep.RemoveEntity>()
                .map { it.entity }
                .toList(),
        )
    }

    // Entity cargo is HolderId.Entity. Parallel destroy vs ledger -> "entity no longer exists".
    val (entityDestroy, rest) = composite.destroy.partition { it is StructureStep.RemoveEntity }
    val (deferredBlocks, prompt) = rest.partition { it.at in contested }

    // SetBlock into a hanging's cell pops it (vanilla drop = dupe). Wait until hangings are gone
    val hangingCells = HashSet<BlockPos>(entityDestroy.size)
    for (step in entityDestroy) hangingCells += step.at

    // Creates on contested cells too: grass-over-dispenser is a SetBlock while the ledger
    // still empties arrows. Run beside the take and it refuses, then grass never lands.
    val (createNow, createLater) = composite.create.partition {
        it !is StructureStep.SetBlock || (it.at !in hangingCells && it.at !in contested)
    }
    val deferred = entityDestroy + deferredBlocks + createLater

    return ApplyLayout(respawning, keepCargoFor, ledgerCargoFor, ledgerHeldBy, entityDestroy, prompt, createNow, deferred)
}
