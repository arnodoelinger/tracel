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

/** The holder the ledger books this step's hull or container under, and the placed one a job's plan names it by. */
internal fun StructureStep.cargoHolders(): Pair<HolderId, HolderId>? = when {
    this is StructureStep.RemoveEntity -> HolderId.Entity(entity) to HolderId.PlacedEntity(entity)

    this is StructureStep.SetBlock && target.isAirLike && !expected.isAirLike &&
            containerBlockNamed(expected.data.value) ->
        HolderId.Block(at.world, at.x, at.y, at.z) to HolderId.PlacedBlock(at.world, at.x, at.y, at.z)

    else -> null
}

/**
 * Hulls and containers among [steps] that will still hold booked items once the withdrawals and deliveries in
 * [deltas] have run: removing them would destroy what is left in them. Named as the job's plan names them.
 */
internal suspend fun TracelServices.leftHolding(
    steps: List<StructureStep>,
    deltas: Map<HolderId, Map<ItemKey, Long>>,
): Set<HolderId> {
    val cargo = LinkedHashMap<HolderId, HolderId>()
    for (step in steps) step.cargoHolders()?.let { (holder, placed) -> cargo[holder] = placed }
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
