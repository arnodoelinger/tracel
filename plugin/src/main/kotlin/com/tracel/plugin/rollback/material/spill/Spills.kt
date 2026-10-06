package com.tracel.plugin.rollback.material.spill

import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.Quantity
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.plugin.listener.support.drop.dropTracked
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.item.Moves
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bukkit.Location
import org.bukkit.World
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

private val spillRecords = ConcurrentHashMap.newKeySet<Job>()

/**
 * Overflow onto the ground at [at], tracked. Ledger already credited the chest; drop + record
 * or diamonds vanish. Caller is already on the region.
 *
 * Recorded now.
 */
internal fun MaterialRestorer.spillInRegion(
    holder: HolderId,
    moves: Moves,
    world: World,
    at: Location,
    sink: MutableCollection<Spill>
) {
    if (moves.overflow.isEmpty()) return
    val where = BlockPos(WorldId(world.uid), at.blockX, at.blockY, at.blockZ)
    val spilled =
        moves.overflow.map { (itemKey, stack) -> Spill(holder, services.dropTracked(stack, itemKey, world, at), where) }
    sink += spilled
    recordSpilled(holder, where, spilled)
}

/**
 * Books piles already on the ground as moved out of [holder]; [recordSpills] waits for it.
 *
 * Called on the region that just spawned them, and the piles stay frozen until the books know them.
 */
internal fun MaterialRestorer.recordSpilled(holder: HolderId, where: BlockPos, spilled: List<Spill>) {
    if (spilled.isEmpty()) return
    val piles = spilled.map { it.delta.holder }
    services.frozen.freeze(piles)
    val recording = services.scope.launch {
        try {
            recordSpillGroup(holder, where, spilled)
        } finally {
            services.frozen.thaw(piles)
        }
    }
    spillRecords += recording
    recording.invokeOnCompletion { spillRecords -= recording }
}

/** Waits until every spill so far is on the books. */
internal suspend fun MaterialRestorer.recordSpills(sink: Collection<Spill>) {
    if (sink.isEmpty()) return
    for (recording in spillRecords.toList()) recording.join()
}

private suspend fun MaterialRestorer.recordSpillGroup(holder: HolderId, at: BlockPos, spills: List<Spill>) {
    val flows = spills.map { Flow(it.delta.itemKey, Quantity(it.delta.delta), holder, it.delta.holder, FlowKind.MOVE) }
    runCatching {
        services.atomically {
            // the pile is real either way: a holder that lost its lots meanwhile mints what it no longer has
            services.capture.recordDirect(
                flows,
                System.currentTimeMillis(),
                CauseKind.ROLLBACK,
                null,
                at
            ) { it == holder }
        }
    }.onFailure {
        logger.log(Level.WARNING, "material overflowed onto the ground and the move could not be recorded", it)
    }
}
