package com.tracel.plugin.rollback.structure

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import java.util.concurrent.atomic.AtomicIntegerArray

/** Claim owned groups without touching the world. */
internal fun claimOwned(
    index: Int,
    groups: List<List<StructureStep>>,
    claimed: AtomicIntegerArray,
    owns: (StructureStep) -> Boolean,
): List<StructureStep> {
    val mine = ArrayList<StructureStep>()
    for (other in groups.indices) {
        if (other != index && (claimed.get(other) != 0 || !owns(groups[other].first()))) continue
        if (!claimed.compareAndSet(other, 0, 1)) continue
        mine += groups[other]
    }
    return mine
}
