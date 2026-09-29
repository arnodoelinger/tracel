package com.tracel.plugin.rollback.material

import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.listener.support.entity.LiveProjectile
import com.tracel.plugin.rollback.material.holder.*
import com.tracel.plugin.rollback.material.item.WornStacks
import com.tracel.plugin.rollback.material.spill.Spill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.entity.Projectile

/** One holder's move. */
internal suspend fun MaterialRestorer.applyTo(
    holder: HolderId,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    job: RollbackJobId,
    sink: MutableCollection<Spill>,
    asOf: Long? = null,
    worn: WornStacks? = null,
): ApplyResult = reported {
    val ordered = if (deltas.values.any { it < 0L } && deltas.values.any { it > 0L }) {
        deltas.entries.sortedBy { it.value > 0L }.associate { it.toPair() }
    } else {
        deltas
    }
    dispatch(holder, ordered, forms, job, sink, asOf, worn)
}

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
    worn: WornStacks? = null,
): ApplyResult = when (holder) {
    is HolderId.Player -> applyToPlayer(holder, deltas, forms, job, sink, worn)
    is HolderId.EnderChest -> applyToEnderChest(holder, deltas, forms, job, sink, worn)
    is HolderId.Block -> applyToContainer(holder, deltas, forms, sink, asOf, worn) ?: ApplyResult.Ok

    is HolderId.ItemEntity -> takeGroundItem(holder, deltas, worn) ?: ApplyResult.Ok

    is HolderId.PlacedEntity -> takeProjectile(holder, deltas)?.let(ApplyResult::Failed) ?: ApplyResult.Ok
    is HolderId.PlacedBlock -> ApplyResult.Ok
    is HolderId.Entity -> fillEntityCargo(holder, deltas, forms, sink, asOf, worn) ?: ApplyResult.Ok

    is HolderId.Source, is HolderId.Sink, is HolderId.Escrow -> ApplyResult.Ok
}

private suspend fun MaterialRestorer.takeProjectile(
    holder: HolderId.PlacedEntity,
    deltas: Map<ItemKey, Long>
): String? {
    if (holder.uuid !in LiveProjectile || deltas.values.any { it > 0L }) return null
    return withContext(services.schedulers.entity(holder.uuid)) {
        val projectile =
            Bukkit.getEntity(holder.uuid) as? Projectile ?: return@withContext "the projectile is no longer there"
        LiveProjectile.remove(holder.uuid)
        services.selfManagedWorld.whileRestoring { projectile.remove() }
        null
    }
}
