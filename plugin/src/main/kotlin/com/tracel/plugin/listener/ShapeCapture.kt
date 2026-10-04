package com.tracel.plugin.listener

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Unstable
import com.tracel.engine.world.BlockEdit
import com.tracel.engine.world.BlockEdits
import com.tracel.engine.world.EntityChange
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import java.util.concurrent.ConcurrentHashMap

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
     * Identical before / after is not a change. A cell a fluid batch is still waiting to read is
     * reported from where that batch first saw it, see [throughPendingFlow]. Plain blocks go on the
     * 24-byte ring; signs and tile extras do not fit a slot, wait beside the ring behind a marker and
     * drain in turn.
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

        val seen = if (pending.isEmpty()) edits else edits.map(::throughPendingFlow)
        val real = seen.filter { it.before != it.after }
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
        val readNanos = System.nanoTime()
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
                        group.filter { !services.selfManagedWorld.wroteSince(it.second, readNanos) }.map { (block, at, shape) ->
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

    private class FlowCell(
        val block: Block,
        val shape: BlockShape,
        val readNanos: Long,
        val cause: CauseKind,
        val causedBy: HolderId?,
    )

    private class FlowBatch(val world: WorldId, val epochMillis: Long) {
        val cells = ConcurrentHashMap<BlockPos, FlowCell>()
    }

    private val flows = ConcurrentHashMap<Any, FlowBatch>()
    private val pending = ConcurrentHashMap<BlockPos, FlowBatch>()

    /**
     * [reread] for fluid flow and fluid level changes, one task per chunk per tick instead of one
     * per event: an ocean draining fires tens of thousands a second, each with its own scheduled task.
     * A cell keeps the shape it had when its first event fired, and is read once, a tick later, for
     * where all of that tick's flowing left it.
     *
     * It was made as one batch per region and tick, so whoever the flow is put down to: a cell the
     * player's water and the world's both reached in one tick sat in two batches, and the second one's
     * look at it — water already — wiped out the first one's air -> water. The cell keeps the first event's
     * attribution.
     *
     * Region thread of [block] only, which is also the only thread the batch's task runs on.
     */
    fun flowed(block: Block, cause: CauseKind, causedBy: HolderId?) {
        if (restoring) return
        val at = block.toBlockPos()
        val key = at.regionKey()
        var batch = flows[key]
        if (batch == null) {
            val fresh = FlowBatch(at.world, System.currentTimeMillis())
            flows[key] = fresh
            batch = fresh
            val ticket = services.pendingCaptures.owed()
            Bukkit.getRegionScheduler().runDelayed(services.plugin, block.location, {
                try {
                    flows.remove(key, fresh)
                    for (pos in fresh.cells.keys) pending.remove(pos, fresh)
                    val byWhom = LinkedHashMap<Pair<CauseKind, HolderId?>, MutableList<BlockEdit>>()
                    for ((pos, cell) in fresh.cells) {
                        if (services.selfManagedWorld.wroteSince(pos, cell.readNanos)) continue
                        byWhom.getOrPut(cell.cause to cell.causedBy) { ArrayList() } +=
                            BlockEdit(pos, cell.shape, cell.block.toShape())
                    }
                    for ((who, changed) in byWhom) {
                        edits(ActionKind.BLOCK_CHANGE, who.first, who.second, fresh.world, changed, fresh.epochMillis)
                    }
                } finally {
                    services.pendingCaptures.done(ticket)
                }
            }, 1L)
        }
        val first = FlowCell(block, block.toShape(), System.nanoTime(), cause, causedBy)
        if (batch.cells.putIfAbsent(at, first) == null) pending[at] = batch
    }

    /** An entity appeared, changed pose, or vanished. Same restore skip as blocks. */
    fun entity(change: EntityChange) {
        if (restoring) return
        services.entityCapture.offer(change)
    }

    private fun throughPendingFlow(edit: BlockEdit): BlockEdit {
        val batch = pending[edit.at] ?: return edit
        var before = edit.before
        batch.cells.computeIfPresent(edit.at) { _, cell ->
            before = cell.shape
            FlowCell(cell.block, edit.after, System.nanoTime(), cell.cause, cell.causedBy)
        }
        return if (before == edit.before) edit else edit.copy(before = before)
    }
}
