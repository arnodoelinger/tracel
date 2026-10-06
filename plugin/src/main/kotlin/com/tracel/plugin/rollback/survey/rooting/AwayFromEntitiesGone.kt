package com.tracel.plugin.rollback.survey.rooting

import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SourceKind
import com.tracel.model.lot.LotId
import java.util.*

/**
 * Adjusts the current rollback target by reassigning lots that belong to entities
 * or placed entities that are part of the specified set of gone UUIDs.
 */
internal fun RollbackTarget.awayFromEntitiesGone(gone: Set<UUID>): RollbackTarget {
    if (gone.isEmpty() || this !is RollbackTarget.PerRoot) return this
    var changed = false
    val next = HashMap<LotId, HolderId>(byRoot.size)
    for ((lot, dest) in byRoot) {
        val uuid = (dest as? HolderId.Entity)?.uuid ?: (dest as? HolderId.PlacedEntity)?.uuid
        if (uuid != null && uuid in gone) {
            next[lot] = HolderId.Source(SourceKind.UNATTRIBUTED)
            changed = true
        } else {
            next[lot] = dest
        }
    }
    return if (changed) RollbackTarget.PerRoot(next) else this
}
