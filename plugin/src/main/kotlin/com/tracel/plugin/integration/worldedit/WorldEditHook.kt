package com.tracel.plugin.integration.worldedit

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.WorldEditException
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.event.extent.EditSessionEvent
import com.sk89q.worldedit.extent.AbstractDelegateExtent
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.util.eventbus.Subscribe
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.block.BlockStateHolder
import com.tracel.annotations.CauseKind
import com.tracel.engine.world.BlockEdit
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.*
import com.tracel.plugin.util.Warnings
import com.tracel.plugin.util.ownsChunkAt
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("WorldEditHook")

/** A session that has gone this long without writing a block is done, as far as the log is concerned. */
private const val IDLE_NANOS = 200_000_000L

/** How often idle sessions are looked for. */
private const val SWEEP_MILLIS = 100L

/** One flush stays inside a single ring slot, so it takes the fast path. */
private const val MAX_BATCH = 4_000

/**
 * Logs what players do with `WorldEdit` and `FAWE`.
 *
 * Neither fires a `Bukkit` event for the blocks it writes, so the log has to stand inside their extent
 * stack: every edit session is offered to [onEditSession], and one a player started gets a
 * [LoggingExtent] wrapped around the place its blocks are finally written. That extent sees each
 * block with its real previous state and the state it is given.
 *
 * Only edits by players are logged. Sessions with no actor or a console one are plugins doing
 * their own work (an arena reset, a generator), which are not anybody's history.
 */
internal class WorldEditHook(private val services: TracelServices) : AutoCloseable {
    private val live = ConcurrentHashMap.newKeySet<LoggingExtent>()
    private val shapes = ConcurrentHashMap<BlockState, BlockShape>()
    private val tiles = ConcurrentHashMap<BlockType, Boolean>()
    private var sweeper: ScheduledTask? = null

    /** Subscribes to the event bus and starts looking for sessions that went quiet. */
    fun register() {
        WorldEdit.getInstance().eventBus.register(this)
        sweeper = Bukkit.getAsyncScheduler().runAtFixedRate(
            services.plugin,
            { sweep() },
            SWEEP_MILLIS,
            SWEEP_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** `WorldEdit` calls this once per stage of every edit session; only the one nearest the world matters. */
    @Subscribe
    fun onEditSession(event: EditSessionEvent) {
        if (event.stage != EditSession.Stage.BEFORE_CHANGE) return
        try {
            val actor = event.actor ?: return
            if (!actor.isPlayer) return
            val world = event.world ?: return
            val bukkitWorld = BukkitAdapter.adapt(world)
            event.extent = LoggingExtent(event.extent, bukkitWorld, actor.uniqueId)
        } catch (failure: Throwable) {
            Warnings.once(logger, "wrap") { "a WorldEdit session could not be logged: $failure" }
            logger.log(Level.FINE, "WorldEdit session not wrapped", failure)
        }
    }

    override fun close() {
        sweeper?.cancel()
        sweeper = null
        runCatching { WorldEdit.getInstance().eventBus.unregister(this) }
        for (session in live.toList()) session.flush()
        live.clear()
    }

    private fun sweep() {
        val now = System.nanoTime()
        for (session in live) session.flushIfIdle(now)
    }

    private fun shapeOf(state: BlockState): BlockShape = shapes.getOrPut(state) {
        val raw = state.asString
        BlockShape(BlockDataKey(BlockDataCache.of(BlockDataKey(raw))?.asString ?: raw))
    }

    private class Before(val shape: BlockShape, val cargo: Cargo? = null)

    private class Cargo(val holder: HolderId, val totals: Map<ItemKey, Long>)

    private inner class LoggingExtent(
        extent: Extent,
        private val world: World,
        player: UUID,
    ) : AbstractDelegateExtent(extent) {
        private val worldId = WorldId(world.uid)
        private val by = HolderId.Player(player)
        private val buffer = EditBuffer(worldId)
        private val lock = Any()
        private var ticket = 0L
        private var since = 0L
        private var lastWrite = 0L

        @Throws(WorldEditException::class)
        override fun <T : BlockStateHolder<T>> setBlock(position: BlockVector3, block: T): Boolean {
            val before = try {
                before(position)
            } catch (failure: Throwable) {
                Warnings.once(logger, "before") { "a block WorldEdit is about to change could not be read: $failure" }
                null
            }
            val changed = super.setBlock(position, block)
            if (!changed || before == null) return changed
            try {
                val after = shapeOf(block.toImmutableState())
                note(position, before, after)
            } catch (failure: Throwable) {
                Warnings.once(logger, "note") { "a WorldEdit change could not be logged: $failure" }
            }
            return true
        }

        private fun before(position: BlockVector3): Before {
            val state = getBlock(position)
            val x = position.x()
            val y = position.y()
            val z = position.z()
            if (!ownsChunkAt(world, x, z)) {
                Warnings.once(logger, "async") {
                    "an edit is being made off the server's region threads (FAWE does this); " +
                        "signs, banners and other tile entities it replaces are logged without their details, " +
                        "and containers it replaces without their contents"
                }
                return Before(shapeOf(state))
            }
            val block = world.getBlockAt(x, y, z)
            if (!tiles.getOrPut(state.blockType) { block.mayHaveTile() }) return Before(shapeOf(state))

            val slots = block.cargoSlots()
            val cargo = if (slots != null && slots.holdsAnything()) {
                val holder = block.toHolderId()
                services.material.captureSlotLayout(holder, slots)
                block.cargoTotals()?.takeIf { it.isNotEmpty() }?.let { Cargo(holder, it) }
            } else {
                null
            }
            return Before(block.toShape(), cargo)
        }

        private fun note(position: BlockVector3, before: Before, after: BlockShape) {
            val x = position.x()
            val y = position.y()
            val z = position.z()
            if (before.cargo != null && before.shape.data != after.data) {
                services.material.destroyed(
                    holder = before.cargo.holder,
                    present = before.cargo.totals,
                    at = BlockPos(worldId, x, y, z),
                    cause = CauseKind.PLAYER_ACTION,
                    causedBy = by,
                )
            }
            val full: Boolean
            synchronized(lock) {
                if (buffer.note(x, y, z, before.shape, after)) {
                    ticket = services.pendingCaptures.owed()
                    since = System.currentTimeMillis()
                    live += this
                }
                lastWrite = System.nanoTime()
                full = buffer.size >= MAX_BATCH
            }
            if (full) flush()
        }

        fun flushIfIdle(now: Long) {
            val idle = synchronized(lock) { buffer.size > 0 && now - lastWrite >= IDLE_NANOS }
            if (idle) flush()
        }

        fun flush() {
            val batch: Map<ActionKind, List<BlockEdit>>
            val owed: Long
            val at: Long
            synchronized(lock) {
                if (buffer.size == 0) return
                batch = buffer.drain()
                owed = ticket
                at = since
                live -= this
            }
            try {
                for ((action, edits) in batch) {
                    services.shape.edits(action, CauseKind.PLAYER_ACTION, by, worldId, edits, at)
                }
            } finally {
                services.pendingCaptures.done(owed)
            }
        }
    }
}
