package com.tracel.plugin.rollback.composer

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
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
