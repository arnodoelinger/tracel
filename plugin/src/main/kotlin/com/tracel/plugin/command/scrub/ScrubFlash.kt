package com.tracel.plugin.command.scrub

import com.tracel.plugin.specifics.server.ClientEntities
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * The outline of a changed place, for one player and nobody else: it shows at once, holds,
 * and fades like a burn.
 *
 * The lines live on the client alone: the server makes no entity of them, only the packets.
 *
 * Every call comes from the player's own thread.
 */
internal class ScrubFlash(
    private val plugin: Plugin,
    private val player: Player,
    private val world: World,
) {
    private var closed = false
    private val live = HashSet<Int>()

    /**
     * Draws the [lines] in chunks the player has, which then put themselves out:
     * what the client has not loaded is not drawn, and that is the only limit.
     */
    fun show(lines: List<Outline.Line>) {
        if (closed || !ClientEntities.works) return
        val drawn = ArrayList<Pair<BlockDisplay, Outline.Line>>(lines.size)
        val packets = ArrayList<Any>(lines.size * 2)
        for (line in lines) {
            if (!player.isChunkSent(Chunk.getChunkKey(line.x shr 4, line.z shr 4))) continue
            val display = world.createEntity(Location(world, line.x.toDouble(), line.y.toDouble(), line.z.toDouble()), BlockDisplay::class.java)
            mark(display, line)
            packets.addAll(ClientEntities.appear(display, line.x, line.y, line.z))
            drawn += display to line
        }
        if (drawn.isEmpty()) return
        ClientEntities.send(player, packets)
        val ids = IntArray(drawn.size) { ClientEntities.id(drawn[it].first) }
        ids.forEach(live::add)
        player.scheduler.runDelayed(plugin, { fade(drawn) }, null, HOLD_TICKS)
        player.scheduler.runDelayed(plugin, { gone(ids) }, null, HOLD_TICKS + FADE_TICKS + 1)
    }

    /** Takes away what is still drawn. */
    fun close() {
        closed = true
        if (live.isNotEmpty()) gone(live.toIntArray())
    }

    private fun fade(drawn: List<Pair<BlockDisplay, Outline.Line>>) {
        if (closed) return
        val packets = ArrayList<Any>(drawn.size)
        for ((display, line) in drawn) {
            display.interpolationDelay = 0
            display.interpolationDuration = FADE_TICKS.toInt()
            display.transformation = beam(line, 0f)
            ClientEntities.update(display)?.let(packets::add)
        }
        ClientEntities.send(player, packets)
    }

    private fun gone(ids: IntArray) {
        ids.forEach(live::remove)
        if (player.isOnline) ClientEntities.gone(ids)?.let { ClientEntities.send(player, listOf(it)) }
    }

    private fun mark(display: BlockDisplay, line: Outline.Line) {
        display.block = COLOR.createBlockData()
        display.brightness = Display.Brightness(15, 15)
        val reach = (line.length + 2).toFloat()
        display.displayWidth = reach
        display.displayHeight = reach
        display.transformation = beam(line, THICKNESS)
    }

    private fun beam(line: Outline.Line, thickness: Float): Transformation {
        val size = when (line.axis) {
            Outline.Axis.X -> Vector3f(line.length + thickness, thickness, thickness)
            Outline.Axis.Y -> Vector3f(thickness, line.length + thickness, thickness)
            Outline.Axis.Z -> Vector3f(thickness, thickness, line.length + thickness)
        }
        val shift = -thickness / 2
        return Transformation(Vector3f(shift, shift, shift), Quaternionf(), size, Quaternionf())
    }

    private companion object {
        val COLOR = Material.WHITE_CONCRETE

        const val THICKNESS = 0.05f
        const val HOLD_TICKS = 8L
        const val FADE_TICKS = 12L
    }
}
