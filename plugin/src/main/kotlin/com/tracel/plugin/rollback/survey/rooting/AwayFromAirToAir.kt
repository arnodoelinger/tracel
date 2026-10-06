package com.tracel.plugin.rollback.survey.rooting

import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SourceKind
import com.tracel.model.lot.LotId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.util.holder.blockPos

/**
 * A root that was a chest, then got blown up, then had something else placed in the same spot
 * has nowhere sane to go back to — restoring "into" that cell would either resurrect the wrong
 * block or land in whatever replaced it.
 *
 * For any root sitting on a position in [vanished], swap it to [SourceKind.UNATTRIBUTED] so the
 * item is accounted for without pretending it has a home.
 */
internal fun RollbackTarget.awayFromAirToAir(vanished: Set<BlockPos>): RollbackTarget {
    if (vanished.isEmpty()) return this
    return when (this) {
        is RollbackTarget.Uniform -> this
        is RollbackTarget.PerRoot -> {
            var changed = false
            val next = HashMap<LotId, HolderId>(byRoot.size)
            for ((lot, dest) in byRoot) {
                val pos = dest.blockPos()
                if (pos != null && pos in vanished) {
                    next[lot] = HolderId.Source(SourceKind.UNATTRIBUTED)
                    changed = true
                } else {
                    next[lot] = dest
                }
            }
            if (changed) RollbackTarget.PerRoot(next) else this
        }
    }
}
