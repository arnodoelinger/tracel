package com.tracel.plugin.rollback.composer

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.block.containerBlockNamed
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.specifics.block.isAirLike
import java.util.*

/** Hulls the ledger will fill. */
internal fun filledByLedger(deltas: Map<HolderId, Map<ItemKey, Long>>): Set<UUID> =
    deltas.asSequence()
        .filter { (_, moved) -> moved.values.any { it > 0L } }
        .mapNotNull { (holder, _) -> (holder as? HolderId.Entity)?.uuid }
        .toHashSet()

/** Hulls this job withdraws from. */
internal fun emptiedByLedger(deltas: Map<HolderId, Map<ItemKey, Long>>): Set<UUID> =
    deltas.asSequence()
        .filter { (_, moved) -> moved.values.any { it < 0L } }
        .mapNotNull { (holder, _) -> (holder as? HolderId.Entity)?.uuid }
        .toHashSet()

/** Hulls among [uuids] the ledger still credits. */
internal suspend fun TracelServices.heldByLedger(uuids: Collection<UUID>): Set<UUID> {
    if (uuids.isEmpty()) return emptySet()
    val held = HashSet<UUID>(uuids.size)
    atomically {
        for (uuid in uuids) {
            if (ledger.totalsAt(HolderId.Entity(uuid)).isNotEmpty()) held += uuid
        }
    }
    return held
}

/**
 * Hulls and containers among [steps] that will still hold booked items once the withdrawals and deliveries in
 * [deltas] have run, so removing them is refused, or would destroy what is left in them.
 */
internal suspend fun TracelServices.leftHolding(
    steps: List<StructureStep>,
    deltas: Map<HolderId, Map<ItemKey, Long>>,
): Set<HolderId> {
    val cargo = LinkedHashMap<HolderId, HolderId>()
    for (step in steps) {
        when {
            step is StructureStep.RemoveEntity -> cargo[HolderId.Entity(step.entity)] =
                HolderId.PlacedEntity(step.entity)

            step is StructureStep.SetBlock && step.target.isAirLike && !step.expected.isAirLike &&
                    containerBlockNamed(step.expected.data.value) ->
                cargo[HolderId.Block(step.at.world, step.at.x, step.at.y, step.at.z)] =
                    HolderId.PlacedBlock(step.at.world, step.at.x, step.at.y, step.at.z)
        }
    }
    if (cargo.isEmpty()) return emptySet()
    val left = HashSet<HolderId>()
    atomically {
        for ((holder, placed) in cargo) {
            val held = ledger.totalsAt(holder)
            if (held.isEmpty()) continue
            val moved = deltas[holder].orEmpty()
            if (held.any { (key, amount) -> amount.raw + (moved[key] ?: 0L) > 0L }) left += placed
        }
    }
    return left
}
