package com.tracel.plugin.listener

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Unstable
import com.tracel.engine.world.BlockEdit
import com.tracel.engine.world.BlockEdits
import com.tracel.engine.world.EntityChange
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.listener.support.FluidDisturbance
import com.tracel.plugin.adapter.block.isFluidShape
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.launch
import com.tracel.model.world.BlockPos
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockState

/**
 * How a listener writes the world log.
 *
 * Shape is what occupies a cell or entity UUID: block data, facing, tile / entity NBT with
 * cargo stripped. It is not items. A chest placed is a shape change (air -> chest); the
 * stacks that go into it are [MaterialCapture] and the other log.
 */
@Unstable
class ShapeCapture internal constructor(private val services: TracelServices) {
    /** `true` while rollback is writing the world; skip so restore does not log itself. */
    val restoring: Boolean get() = services.selfManagedWorld.isRestoring

    /**
     * Record a batch of already-snapshotted cell changes.
     *
     * Identical before / after is not a change. Fluid still settling after a restore is the
     * world finishing our write, not new history. Plain blocks go on the 24-byte ring;
     * signs and tile extras do not fit a slot, wait beside the ring behind a marker and drain in turn.
     */
    fun edits(
        action: ActionKind,
        cause: CauseKind,
        causedBy: HolderId?,
        world: WorldId,
        edits: List<BlockEdit>,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        if (restoring) return

        val real = edits.filter {
            it.before != it.after && !(isSettlingFluid(it))
        }
        if (real.isEmpty()) return

        if (real.none { it.before.extras != null || it.after.extras != null }) {
            if (services.gate.blocks(cause, action, causedBy, epochMillis, world, real)) return
        }
        val batch = BlockEdits(action, cause, causedBy, epochMillis, real)
        if (services.gate.parkedBlocks(batch)) return

        val ticket = services.pendingCaptures.owed()
        services.scope.launch {
            services.atomically { services.worldCapture.record(batch) }
        }.invokeOnCompletion { services.pendingCaptures.done(ticket) }
    }

    /** One cell whose [before] and [after] shapes are already known. */
    fun edit(
        block: Block,
        before: BlockShape,
        after: BlockShape,
        action: ActionKind = ActionKind.BLOCK_CHANGE,
        cause: CauseKind = CauseKind.WORLD,
        causedBy: HolderId? = null,
        epochMillis: Long = System.currentTimeMillis(),
    ) = edits(
        action, cause, causedBy, WorldId(block.world.uid),
        listOf(BlockEdit(block.toBlockPos(), before, after)), epochMillis,
    )

    /**
     * This cell is about to be empty: current shape -> air.
     *
     * Call while the block is still there.
     */
    fun removed(block: Block, cause: CauseKind = CauseKind.WORLD, causedBy: HolderId? = null) =
        edit(block, block.toShape(), BlockShape.AIR, ActionKind.BLOCK_BREAK, cause, causedBy)

    /** This cell is becoming [newState]: current shape -> that state's shape. */
    fun became(
        block: Block,
        newState: BlockState,
        cause: CauseKind = CauseKind.WORLD,
        causedBy: HolderId? = null,
    ) = edit(block, block.toShape(), newState.toShape(), ActionKind.BLOCK_CHANGE, cause, causedBy)

    /**
     * The event fired too early to know the final shape. Snapshot [blocks] now, read them
     * again after [delayTicks], and log the difference.
     *
     * The delayed tick runs after [restoring] is already false, so skip scheduling during
     * restore or rollback logs itself.
     *
     * `Folia`: one region cannot read another — group by chunk. Owe pending captures from
     * schedule until that run: the world has moved and the log has not.
     */
    fun reread(
        action: ActionKind,
        cause: CauseKind,
        causedBy: HolderId?,
        blocks: List<Block>,
        delayTicks: Long = 1L,
        keep: (BlockEdit) -> Boolean = { true },
    ) {
        if (restoring) return
        if (blocks.isEmpty()) return
        val epochMillis = System.currentTimeMillis()
        val snapshots = blocks.map { Triple(it, it.toBlockPos(), it.toShape()) }
        for ((_, group) in snapshots.groupBy { it.second.regionKey() }) {
            val world = group.first().second.world
            val ticket = services.pendingCaptures.owed()
            Bukkit.getRegionScheduler().runDelayed(services.plugin, group.first().first.location, {
                try {
                    edits(
                        action,
                        cause,
                        causedBy,
                        world,
                        group.filter { !services.selfManagedWorld.justWrote(it.second) }.map { (block, at, shape) ->
                            BlockEdit(at, shape, block.toShape())
                        }.filter(keep),
                        epochMillis,
                    )
                } finally {
                    services.pendingCaptures.done(ticket)
                }
            }, delayTicks.coerceAtLeast(1L))
        }
    }

    private class FlowBatch(val world: WorldId, val epochMillis: Long) {
        val cells = LinkedHashMap<BlockPos, Pair<Block, BlockShape>>()
    }

    private val flows = ConcurrentHashMap<Triple<Any, CauseKind, HolderId?>, FlowBatch>()

    /**
     * [reread] for fluid flow, one task per chunk per tick instead of one per event: an ocean
     * draining fires tens of thousands a second, each with its own scheduled task.
     *
     * Region thread of [block] only, which is also the only thread the batch's task runs on.
     */
    fun flowed(block: Block, cause: CauseKind, causedBy: HolderId?) {
        if (restoring) return
        val at = block.toBlockPos()
        val key = Triple(at.regionKey(), cause, causedBy)
        var batch = flows[key]
        if (batch == null) {
            val fresh = FlowBatch(at.world, System.currentTimeMillis())
            flows[key] = fresh
            batch = fresh
            val ticket = services.pendingCaptures.owed()
            Bukkit.getRegionScheduler().runDelayed(services.plugin, block.location, {
                try {
                    flows.remove(key, fresh)
                    val edits = fresh.cells.filterKeys { !services.selfManagedWorld.justWrote(it) }
                        .map { (pos, cell) -> BlockEdit(pos, cell.second, cell.first.toShape()) }
                    edits(ActionKind.BLOCK_CHANGE, cause, causedBy, fresh.world, edits, fresh.epochMillis)
                } finally {
                    services.pendingCaptures.done(ticket)
                }
            }, 1L)
        }
        batch.cells.putIfAbsent(at, block to block.toShape())
    }

    /** An entity appeared, changed pose, or vanished. Same restore skip as blocks. */
    fun entity(change: EntityChange) {
        if (restoring) return
        services.entityCapture.offer(change)
    }

    @Suppress("RedundantIf")
    private fun isSettlingFluid(edit: BlockEdit): Boolean {
        if (!edit.before.isFluidOrAir() || !edit.after.isFluidOrAir()) return false
        return FluidDisturbance.isDisturbed(edit.at)
    }

    private fun BlockShape.isFluidOrAir(): Boolean {
        if (isFluidShape(this)) return true
        val value = data.value
        return value == "minecraft:air" || value == "minecraft:cave_air" || value == "minecraft:void_air"
    }
}
