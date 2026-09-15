package com.tracel.plugin.rollback.material.spill

import com.tracel.annotations.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.listener.support.dropTracked
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.item.Moves
import java.util.logging.Level
import org.bukkit.Location
import org.bukkit.World

/**
 * Overflow onto the ground at [at], tracked. Ledger already credited the chest; drop + record
 * or diamonds vanish. Caller is already on the region.
 */
internal fun MaterialRestorer.spillInRegion(holder: HolderId, moves: Moves, world: World, at: Location, sink: MutableCollection<Spill>) {
    if (moves.overflow.isEmpty()) return
    val where = BlockPos(WorldId(world.uid), at.blockX, at.blockY, at.blockZ)
    for ((itemKey, stack) in moves.overflow) {
        sink += Spill(holder, services.dropTracked(stack, itemKey, world, at), where)
    }
}

/** Record spills off-region as [CauseKind.ROLLBACK]. */
internal suspend fun MaterialRestorer.recordSpills(sink: Collection<Spill>) {
    if (sink.isEmpty()) return
    val now = System.currentTimeMillis()
    runCatching {
        services.atomically {
            for ((key, spills) in sink.groupBy { it.holder to it.at }) {
                val (holder, at) = key
                val flows = spills.map { Flow(it.delta.itemKey, Quantity(it.delta.delta), holder, it.delta.holder, FlowKind.MOVE) }
                services.capture.recordDirect(flows, now, CauseKind.ROLLBACK, null, at)
            }
        }
    }.onFailure {
        logger.log(Level.WARNING, "material overflowed onto the ground and the move could not be recorded", it)
    }
}
