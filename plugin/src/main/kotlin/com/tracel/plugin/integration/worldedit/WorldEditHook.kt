package com.tracel.plugin.integration.worldedit

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.WorldEditException
import com.sk89q.worldedit.event.extent.EditSessionEvent
import com.sk89q.worldedit.extent.AbstractDelegateExtent
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.function.mask.Mask
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.util.eventbus.Subscribe
import com.sk89q.worldedit.world.World as WeWorld
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockStateHolder
import com.sk89q.worldedit.world.block.BlockType
import com.tracel.engine.world.edit.BlockEdit
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.*
import com.tracel.plugin.adapter.world.ownsChunkAt
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.util.log.Warnings
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.World

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
 * Neither fires a `Bukkit` event for the blocks it writes, so the log has to stand inside their edit
 * machinery.
 *
 * Only edits by players are logged. Sessions with no actor or a console one are plugins doing
 * their own work (an arena reset, a generator), which are not anybody's history.
 */
internal class WorldEditHook(private val services: TracelServices) : AutoCloseable {
    private val live = ConcurrentHashMap.newKeySet<LoggingExtent>()
    private val tiles = ConcurrentHashMap<BlockType, Boolean>()
    private val fawe = Bukkit.getPluginManager().isPluginEnabled(WorldEditSupport.FAWE)
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
            val bukkitWorld = Bukkit.getWorld(world.name) ?: return
            val extent = event.extent
            if (fawe && extent !is WeWorld) {
                FaweLogging.attach(extent, services, WorldId(bukkitWorld.uid), actor.uniqueId)
            } else {
                event.extent = LoggingExtent(extent, bukkitWorld, actor.uniqueId)
            }
        } catch (failure: Throwable) {
            Warnings.once(logger, "wrap") { "a WorldEdit session could not be logged: $failure" }
            logger.log(Level.FINE, "WorldEdit session not hooked", failure)
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

    private class Before(val shape: BlockShape, val cargo: Cargo? = null)

    private class Cargo(val holder: HolderId, val totals: Map<ItemKey, Long>)

    private inner class LoggingExtent(
        extent: Extent,
        private val world: World,
        player: UUID,
    ) : AbstractDelegateExtent(extent), Extent {
        private val worldId = WorldId(world.uid)
        private val by = HolderId.Player(player)
        private val buffer = EditBuffer(worldId)
        private val lock = Any()
        private var ticket = 0L
        private var since = 0L
        private var lastWrite = 0L

        @Throws(WorldEditException::class)
        override fun <T : BlockStateHolder<T>> setBlock(position: BlockVector3, block: T): Boolean =
            logged(position.x(), position.y(), position.z(), block, position) { super<AbstractDelegateExtent>.setBlock(position, block) }

        @Throws(WorldEditException::class)
        override fun <B : BlockStateHolder<B>> setBlock(x: Int, y: Int, z: Int, block: B): Boolean =
            logged(x, y, z, block, null) { super<AbstractDelegateExtent>.setBlock(x, y, z, block) }

        // region FAWE

        @Throws(WorldEditException::class)
        override fun <B : BlockStateHolder<B>> setBlocks(region: Region, block: B): Int =
            super<Extent>.setBlocks(region, block)

        @Throws(WorldEditException::class)
        override fun setBlocks(region: Region, pattern: Pattern): Int =
            super<Extent>.setBlocks(region, pattern)

        override fun setBlocks(vset: MutableSet<BlockVector3>, pattern: Pattern): Int =
            super<Extent>.setBlocks(vset, pattern)

        @Throws(WorldEditException::class)
        override fun <B : BlockStateHolder<B>> replaceBlocks(region: Region, filter: MutableSet<BaseBlock>?, replacement: B): Int =
            super<Extent>.replaceBlocks(region, filter, replacement)

        @Throws(WorldEditException::class)
        override fun replaceBlocks(region: Region, filter: MutableSet<BaseBlock>?, pattern: Pattern): Int =
            super<Extent>.replaceBlocks(region, filter, pattern)

        @Throws(WorldEditException::class)
        override fun replaceBlocks(region: Region, mask: Mask, pattern: Pattern): Int =
            super<Extent>.replaceBlocks(region, mask, pattern)

        // endregion

        private inline fun logged(
            x: Int,
            y: Int,
            z: Int,
            block: BlockStateHolder<*>,
            at: BlockVector3?,
            write: () -> Boolean,
        ): Boolean {
            val before = try {
                before(at ?: BlockVector3.at(x, y, z), x, y, z)
            } catch (failure: Throwable) {
                Warnings.once(logger, "before") { "a block WorldEdit is about to change could not be read: $failure" }
                null
            }
            val changed = write()
            if (!changed || before == null) return changed
            try {
                note(x, y, z, before, logShape(block.toImmutableState()))
            } catch (failure: Throwable) {
                Warnings.once(logger, "note") { "a WorldEdit change could not be logged: $failure" }
            }
            return true
        }

        private fun before(position: BlockVector3, x: Int, y: Int, z: Int): Before {
            val state = getBlock(position)
            if (!ownsChunkAt(world, x, z)) {
                Warnings.once(logger, "async") {
                    "an edit is being made off the server's region threads (FAWE does this); " +
                        "signs, banners and other tile entities it replaces are logged without their details, " +
                        "and containers it replaces without their contents"
                }
                return Before(logShape(state))
            }
            val block = world.getBlockAt(x, y, z)
            if (!tiles.getOrPut(state.blockType) { block.mayHaveTile() }) return Before(logShape(state))

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

        private fun note(x: Int, y: Int, z: Int, before: Before, after: BlockShape) {
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
