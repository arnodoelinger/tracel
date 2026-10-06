package com.tracel.plugin.rollback.material

import com.tracel.engine.rollback.involution.plan.InvolutionStep
import com.tracel.engine.rollback.involution.plan.physicalDeltasForUndo
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.holder.spawnReturnedDrops
import com.tracel.plugin.rollback.material.item.formsFor
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.material.spill.recordSpills
import com.tracel.plugin.rollback.result.report.RestorationReport
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level

/** Puts material back after the ledger has already undone a job. */
internal suspend fun MaterialRestorer.restoreUndo(
    steps: List<InvolutionStep>,
    job: RollbackJobId,
    noise: Set<LotId>,
    asOf: Long?
): RestorationReport {
    val deltas = physicalDeltasForUndo(steps, noise)

    // Vanished-drop gives wait until the dest is gone
    val later = deltas.filter { (holder, moved) ->
        holder is HolderId.ItemEntity && moved.values.all { it > 0L }
    }
    val now = deltas.filterKeys { it !in later.keys }
    return restore(now, job, asOf = asOf)
}

/** Respawn drops. */
internal suspend fun MaterialRestorer.respawnDrops(
    steps: List<InvolutionStep>,
    job: RollbackJobId,
    hullAt: Map<UUID, HolderId>,
    shortfall: Map<ItemKey, Long> = emptyMap(),
): RestorationReport {
    val work = LinkedHashMap<HolderId.ItemEntity, MutableMap<ItemKey, Long>>()
    val respawnAt = HashMap<HolderId.ItemEntity, HolderId>()
    for (step in steps) {
        if (step !is InvolutionStep.Return) continue
        val drop = step.to as? HolderId.ItemEntity ?: continue
        val from = when (val origin = step.from) {
            is HolderId.Entity -> hullAt[origin.uuid] ?: origin
            is HolderId.PlacedEntity -> hullAt[origin.uuid] ?: origin
            else -> origin
        }
        respawnAt[drop] = from
        work.getOrPut(drop) { mutableMapOf() }.merge(step.itemKey, step.quantity.raw, Long::plus)
    }
    if (work.isEmpty()) return RestorationReport(emptyMap())
    val unfunded = withholdUnfunded(work.entries.associate { (drop, deltas) -> drop as HolderId to deltas }, shortfall)
    if (unfunded.withheld.isNotEmpty()) {
        logger.log(
            Level.WARNING,
            "undo job ${job.raw} put ${unfunded.withheld.size} vanished drop(s) back short: what funded them could not " +
                    "be taken, and respawning them anyway would have duplicated it",
        )
    }
    val funded = unfunded.funded.entries.associate { (holder, deltas) -> holder as HolderId.ItemEntity to deltas }
    if (funded.isEmpty()) return RestorationReport(emptyMap(), shortfall = unfunded.unspent)
    val sink = ConcurrentLinkedQueue<Spill>()
    val forms = formsFor(funded.mapKeys { it.key })
    val outcomes = spawnReturnedDrops(funded, respawnAt, forms, sink)
    val failures = mutableMapOf<HolderId, String>()
    for ((holder, result) in outcomes) {
        if (result is ApplyResult.Failed) failures[holder] = result.reason
    }
    recordSpills(sink)
    if (failures.isNotEmpty()) {
        logger.log(
            Level.WARNING,
            "could not put ${failures.size} vanished drop(s) back on the ground in undo job ${job.raw}; " +
                    "first: ${failures.entries.take(SAMPLED_FAILURES).joinToString("; ") { "${it.key}: ${it.value}" }}",
        )
    }
    return RestorationReport(failures, spilled = sink.size, shortfall = unfunded.unspent)
}
