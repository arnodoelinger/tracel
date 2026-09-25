package com.tracel.plugin.rollback.material

import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.engine.rollback.plan.physicalDeltasForUndo
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.holder.spawnReturnedDrops
import com.tracel.plugin.rollback.material.item.formsFor
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.material.spill.recordSpills
import com.tracel.plugin.rollback.result.report.RestorationReport
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import org.bukkit.Material
import org.bukkit.block.Campfire
import org.bukkit.inventory.InventoryHolder

private val holdsItems = ConcurrentHashMap<String, Boolean>()

/** Puts material back after the ledger has already undone a job. */
internal suspend fun MaterialRestorer.restoreUndo(steps: List<InvolutionStep>, job: RollbackJobId, noise: Set<LotId>, asOf: Long?): RestorationReport {
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
): RestorationReport {
    val work = LinkedHashMap<HolderId.ItemEntity, MutableMap<ItemKey, Long>>()
    val respawnAt = HashMap<HolderId.ItemEntity, HolderId>()
    for (step in steps) {
        if (step !is InvolutionStep.Return) continue
        val drop = step.to as? HolderId.ItemEntity ?: continue
        if (step.from is HolderId.PlacedBlock && isContainerBlockItem(step.itemKey)) continue
        val from = when (val origin = step.from) {
            is HolderId.Entity -> hullAt[origin.uuid] ?: origin
            is HolderId.PlacedEntity -> hullAt[origin.uuid] ?: origin
            else -> origin
        }
        respawnAt[drop] = from
        work.getOrPut(drop) { mutableMapOf() }.merge(step.itemKey, step.quantity.raw, Long::plus)
    }
    if (work.isEmpty()) return RestorationReport(emptyMap())
    val sink = ConcurrentLinkedQueue<Spill>()
    val forms = formsFor(work.mapKeys { it.key })
    val outcomes = spawnReturnedDrops(work, respawnAt, forms, sink)
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
    return RestorationReport(failures, spilled = sink.size)
}

/** Whether [itemKey] is a container placed as a block. Asks the block itself, so a new one is not missed. */
internal fun isContainerBlockItem(itemKey: ItemKey): Boolean = holdsItems.computeIfAbsent(itemKey.material) { name ->
    val material = Material.getMaterial(name)?.takeIf { it.isBlock } ?: return@computeIfAbsent false
    runCatching {
        val state = material.createBlockData().createBlockState()
        state is InventoryHolder || state is Campfire
    }.getOrDefault(false)
}
