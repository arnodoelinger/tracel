package com.tracel.plugin.rollback.material

import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.holder.applyToContainer
import com.tracel.plugin.rollback.material.holder.applyToEnderChest
import com.tracel.plugin.rollback.material.holder.applyToPlayer
import com.tracel.plugin.rollback.material.holder.fillEntityCargo
import com.tracel.plugin.rollback.material.holder.takeGroundItem
import com.tracel.plugin.rollback.material.spill.Spill
import kotlinx.coroutines.CancellationException

/** One holder's move. */
internal suspend fun MaterialRestorer.applyTo(
    holder: HolderId,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    job: RollbackJobId,
    sink: MutableCollection<Spill>,
    asOf: Long? = null,
): ApplyResult = reported { dispatch(holder, deltas, forms, job, sink, asOf) }

/** Runs [work] and turns any thrown failure into [ApplyResult.Failed]. */
internal suspend fun MaterialRestorer.reported(work: suspend () -> ApplyResult): ApplyResult = try {
    work()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    ApplyResult.Failed(failure.message ?: failure::class.java.simpleName)
}

/** Routes one holder's move to the restore logic for its kind. */
internal suspend fun MaterialRestorer.dispatch(
    holder: HolderId,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    job: RollbackJobId,
    sink: MutableCollection<Spill>,
    asOf: Long? = null,
): ApplyResult = when (holder) {
    is HolderId.Player -> applyToPlayer(holder, deltas, forms, job, sink)
    is HolderId.EnderChest -> applyToEnderChest(holder, deltas, forms, job, sink)
    is HolderId.Block -> applyToContainer(holder, deltas, forms, sink, asOf)?.let(ApplyResult::Failed) ?: ApplyResult.Ok
    is HolderId.ItemEntity -> takeGroundItem(holder, deltas)?.let(ApplyResult::Failed) ?: ApplyResult.Ok

    is HolderId.PlacedBlock, is HolderId.PlacedEntity -> ApplyResult.Ok
    is HolderId.Entity -> fillEntityCargo(holder, deltas, forms, sink, asOf)?.let(ApplyResult::Failed) ?: ApplyResult.Ok
    is HolderId.Source, is HolderId.Sink, is HolderId.Escrow -> ApplyResult.Ok
}
