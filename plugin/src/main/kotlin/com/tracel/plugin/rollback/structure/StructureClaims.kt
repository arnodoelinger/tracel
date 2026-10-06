package com.tracel.plugin.rollback.structure

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.world.ownsChunkAt
import java.util.concurrent.atomic.AtomicIntegerArray

/** Despawn a sailed boat on its current chunk. */
internal fun StructureRestorer.dispatchAt(step: StructureStep): BlockPos {
    if (step is StructureStep.RemoveEntity) {
        val here = services.whereabouts.at(step.entity) ?: return step.at
        return BlockPos(here.world, here.x, here.y, here.z)
    }
    return step.at
}

/** Claim every group this `Folia` region thread owns. */
internal fun StructureRestorer.claim(
    index: Int,
    groups: List<List<StructureStep>>,
    claimed: AtomicIntegerArray,
    ready: AtomicIntegerArray? = null,
): List<StructureStep> = claimOwned(index, groups, claimed, ready) { ownsChunkAt(dispatchAt(it)) }

/** Claim owned groups without touching the world. */
private fun claimOwned(
    index: Int,
    groups: List<List<StructureStep>>,
    claimed: AtomicIntegerArray,
    ready: AtomicIntegerArray?,
    owns: (StructureStep) -> Boolean,
): List<StructureStep> {
    if (claimed.get(index) != 0) return emptyList()
    val mine = ArrayList<StructureStep>()
    for (other in groups.indices) {
        if (other != index && (claimed.get(other) != 0 || (ready != null && ready.get(other) == 0) ||
                    !owns(groups[other].first()))
        ) continue
        if (!claimed.compareAndSet(other, 0, 1)) continue
        mine += groups[other]
    }
    return mine
}
