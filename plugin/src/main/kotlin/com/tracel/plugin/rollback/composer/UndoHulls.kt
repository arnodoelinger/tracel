package com.tracel.plugin.rollback.composer

import com.tracel.engine.rollback.job.record.RollbackJobRecord
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.plugin.util.holder.blockPos

/** Records the position of an entity being spawned during the rollback process. */
internal fun RollbackComposer.rememberHull(step: StructureStep.SpawnEntity) {
    services.whereabouts.remember(step.entity, HolderId.Block(step.at.world, step.at.x, step.at.y, step.at.z))
}

internal fun RollbackJobRecord.touches(): Set<Any> = buildSet {
    for (step in create + destroy) add(step.at)
    for (holder in plan.holders) touch(holder)
    when (val target = target) {
        is RollbackTarget.Uniform -> touch(target.holder)
        is RollbackTarget.PerRoot -> for (holder in target.byRoot.values) touch(holder)
    }
}

private fun MutableSet<Any>.touch(holder: HolderId) {
    if (holder is HolderId.Source || holder is HolderId.Sink || holder is HolderId.Escrow) return
    add(holder.blockPos() ?: holder)
}
