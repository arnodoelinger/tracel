package com.tracel.plugin.command.scrub

import com.tracel.api.actor.Actor
import com.tracel.plugin.i18n.tr
import io.papermc.paper.math.Position
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.atomic.AtomicBoolean

/** One player scrubbing the past with the mouse wheel. */
internal class Scrub(
    private val plugin: Plugin,
    private val player: Player,
    private val world: World,
    private val timeline: ScrubTimeline,
    private val slot: Int,
) {
    private var depth = 0
    private var wanted = 0
    private var last = slot
    private var asked = 0L
    private val flash = ScrubFlash(plugin, player, world)
    private var playing = false
    private var dirty = false
    private var ticks = 0L
    private var task: ScheduledTask? = null
    private val overlay = HashMap<Long, IntIntMap>()
    private val over = AtomicBoolean()

    /** Begins listening to the wheel and drawing what it asks for. */
    fun start() {
        task = player.scheduler.runAtFixedRate(plugin, { tick() }, { end(restore = false) }, 1L, 1L)
        bar()
    }

    /**
     * The client asked for hotbar slot [now], and the server refused: the selection stays on [slot].
     *
     * The client moves its selection before we can undo it, so a notch is measured from the previous request
     * while the last refusal is still on its way, and from [slot] once it has landed.
     * That takes a round trip, so it is told by the ping.
     * A notch of the wheel is one step, ten of them with [coarse].
     */
    fun scroll(now: Int, coarse: Boolean) {
        val time = System.nanoTime()
        val landed = time - asked > (player.ping.coerceAtLeast(0) + SETTLE_MILLIS) * 1_000_000L
        val from = if (landed) slot else last
        asked = time
        last = now
        var step = Math.floorMod(now - from, HOTBAR)
        if (step > HOTBAR / 2) step -= HOTBAR
        if (step == 0) return
        playing = false
        wanted = (wanted + step * if (coarse) COARSE else 1).coerceIn(0, timeline.size)
    }

    /**
     * Plays the past forward as a timelapse, or stops it.
     *
     * From now, it first goes all the way back.
     */
    fun toggle() {
        if (playing) {
            playing = false
            return
        }
        if (depth == 0) wanted = timeline.size
        playing = timeline.size > 0
    }

    /** What the player touched, so what they see may have been put back. */
    fun touched() {
        dirty = true
    }

    /** The server sent the player chunk ([chunkX], [chunkZ]), and with it the real blocks. */
    fun sent(chunkX: Int, chunkZ: Int) {
        if (over.get()) return
        player.scheduler.runDelayed(plugin, { if (!over.get()) redraw(Packed.chunk(chunkX, chunkZ)) }, null, SENT_TICKS)
    }

    /**
     * Stops.
     *
     * With [restore] the player is shown the real blocks again.
     */
    fun end(restore: Boolean) {
        if (!over.compareAndSet(false, true)) return
        task?.cancel()
        flash.close()
        if (restore) resync()
        overlay.clear()
        timeline.close()
    }

    private fun tick() {
        if (over.get()) return
        ticks++
        if (playing && depth == wanted && ticks % PLAY_TICKS == 0L) {
            if (wanted > 0) wanted-- else playing = false
        }
        val moved = advance()
        if (moved || ticks % BAR_TICKS == 0L) bar()
        if (dirty || ticks % REFRESH_TICKS == 0L) {
            dirty = false
            near()
        }
    }

    @Suppress("UnstableApiUsage")
    private fun advance(): Boolean {
        if (depth == wanted) return false
        val direction = if (wanted > depth) 1 else -1
        var at = depth
        var weight = 0L
        while (at != wanted) {
            val cost = timeline.entries(if (direction > 0) at else at - 1)
            if (at != depth && weight + cost > BUDGET) break
            weight += cost
            at += direction
        }

        val batch = HashMap<Long, Int>()
        timeline.move(depth, at) { pos, state -> batch[pos] = state }
        depth = at

        val send = HashMap<Position, BlockData>(batch.size * 2)
        for ((pos, state) in batch) {
            val data = timeline.data(state) ?: continue
            send[at(pos)] = data
            overlay.getOrPut(Packed.chunkOf(pos)) { IntIntMap() }.put(Packed.local(pos), state)
        }
        player.sendMultiBlockChange(send)
        if (!playing) flash.show(Outline.of(batch.keys))
        if (depth == 0) {
            resync()
            overlay.clear()
        }
        return true
    }

    private fun near() {
        val here = player.location
        val chunkX = here.blockX shr 4
        val chunkZ = here.blockZ shr 4
        for (dx in -NEAR..NEAR) for (dz in -NEAR..NEAR) redraw(Packed.chunk(chunkX + dx, chunkZ + dz))
    }

    @Suppress("UnstableApiUsage")
    private fun redraw(chunk: Long) {
        val blocks = overlay[chunk] ?: return
        val send = HashMap<Position, BlockData>(blocks.size * 2)
        blocks.forEach { local, state ->
            val data = timeline.data(state) ?: return@forEach
            send[Position.block(Packed.localX(chunk, local), Packed.localY(local), Packed.localZ(chunk, local))] = data
        }
        player.sendMultiBlockChange(send)
    }

    /**
     * Shows the real blocks again where the overlay is, reading them on the regions that own them.
     * Chunks that are not loaded are left alone: the player gets them fresh when they are.
     */
    @Suppress("UnstableApiUsage")
    private fun resync() {
        val shown = overlay.mapValues { (_, blocks) ->
            IntArray(blocks.size).also { into ->
                var next = 0
                blocks.forEach { local, _ -> into[next++] = local }
            }
        }
        for ((chunk, locals) in shown) {
            val chunkX = Packed.chunkX(chunk)
            val chunkZ = Packed.chunkZ(chunk)
            for (batch in locals.asList().chunked(RESYNC_BATCH)) {
                Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ) {
                    if (!player.isOnline || !world.isChunkLoaded(chunkX, chunkZ)) return@run
                    val real = HashMap<Position, BlockData>(batch.size * 2)
                    for (local in batch) {
                        val x = Packed.localX(chunk, local)
                        val y = Packed.localY(local)
                        val z = Packed.localZ(chunk, local)
                        real[Position.block(x, y, z)] = world.getBlockAt(x, y, z).blockData
                    }
                    player.sendMultiBlockChange(real)
                }
            }
        }
    }

    private fun bar() {
        player.sendActionBar(
            if (depth == 0) tr("scrub.bar.now", "total" to timeline.size) else {
                val step = timeline.step(depth)
                tr(
                    "scrub.bar.back",
                    "depth" to depth,
                    "total" to timeline.size,
                    "ago" to ago(System.currentTimeMillis() - step.oldest.toEpochMilli()),
                    "who" to who(step.actor),
                    "n" to step.size,
                    "block" to step.block.substringAfter(':').replace('_', ' '),
                    "play" to if (playing) tr("scrub.bar.playing") else Component.empty(),
                )
            },
        )
    }

    private fun who(actor: Actor?): Component = when (actor) {
        is Actor.Player -> Component.text(Bukkit.getOfflinePlayer(actor.uuid).name ?: actor.uuid.toString().take(8))
        is Actor.Plugin -> Component.text(actor.name)
        is Actor.Entity, is Actor.Block, is Actor.Addon -> tr("scrub.who.thing")
        Actor.Natural, null -> tr("scrub.who.world")
    }

    private fun ago(millis: Long): Component {
        val seconds = (millis / 1000).coerceAtLeast(0)
        val (unit, n) = when {
            seconds < 60 -> "s" to seconds
            seconds < 3_600 -> "m" to seconds / 60
            seconds < 86_400 -> "h" to seconds / 3_600
            seconds < 31_536_000 -> "d" to seconds / 86_400
            else -> "y" to seconds / 31_536_000
        }
        return tr("lookup.ago.$unit", "n" to "%02d".format(n))
    }

    @Suppress("UnstableApiUsage")
    private fun at(pos: Long): Position = Position.block(Packed.x(pos), Packed.y(pos), Packed.z(pos))

    private companion object {
        const val HOTBAR = 9
        const val COARSE = 10

        /** Slack on top of the ping before a refused slot is taken as landed: one tick and some jitter. */
        const val SETTLE_MILLIS = 75L

        /**
         * Most block changes one tick moves;
         * a bigger notch is spread over the ticks that follow, a single step is never cut.
         */
        const val BUDGET = 8_192L

        const val PLAY_TICKS = 2L
        const val BAR_TICKS = 20L
        const val REFRESH_TICKS = 40L

        /** How many chunks around the player that reaches. */
        const val NEAR = 2

        /** Ticks to wait after the server sent a chunk, so the fake blocks land after it. */
        const val SENT_TICKS = 2L

        /** Blocks one region task reads back. */
        const val RESYNC_BATCH = 8_192
    }
}
