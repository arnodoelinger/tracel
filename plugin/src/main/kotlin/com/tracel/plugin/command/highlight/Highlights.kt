package com.tracel.plugin.command.highlight

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.plugin.adapter.block.BlockDataCache
import io.papermc.paper.math.Position
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What a player is shown of a rollback, and nothing anybody else sees.
 *
 * Ghost blocks are block-change packets: the world is not touched, and what was there is sent back when the time is up.
 */
internal class Highlights(private val plugin: Plugin) {
    private val ghosts = ConcurrentHashMap<UUID, Ghost>()

    private class Entry(val at: Location, val ghost: BlockData, val was: BlockData)

    private class Ghost(val world: World, val entries: List<Entry>) {
        var task: ScheduledTask? = null
    }

    /**
     * Shows what the rollback would do to [player] alone, for [seconds]: [create] steps put blocks back, [destroy]
     * steps take them away. Only blocks; entities and items have no ghost.
     *
     * @return how many blocks were drawn.
     */
    @Suppress("UnstableApiUsage")
    fun ghost(player: Player, create: List<StructureStep>, destroy: List<StructureStep>, seconds: Int): Int {
        val world = player.world
        val drawn = LinkedHashMap<Location, Entry>()
        for (step in create.asSequence() + destroy.asSequence()) {
            val set = step as? StructureStep.SetBlock ?: continue
            val target = BlockDataCache.of(set.target.data) ?: continue
            val was = BlockDataCache.of(set.expected.data) ?: continue
            val at = Location(world, set.at.x.toDouble(), set.at.y.toDouble(), set.at.z.toDouble())
            drawn[at] = Entry(at, target, was)
        }
        if (drawn.isEmpty()) return 0
        val entries = drawn.values.toList()
        val shown = entries.associate { it.at.toBlock() to it.ghost }

        player.scheduler.run(plugin, {
            clearGhost(player)
            val ghost = Ghost(world, entries)
            ghosts[player.uniqueId] = ghost
            var half = 0
            val last = seconds * 20 / HALF_TICKS
            ghost.task = player.scheduler.runAtFixedRate(plugin, { task ->
                if (!player.isOnline || player.world != world || half >= last) {
                    task.cancel()
                    clearGhost(player)
                    return@runAtFixedRate
                }
                player.sendMultiBlockChange(if (half % 2 == 0) shown else real(ghost))
                half++
            }, null, 1L, HALF_TICKS)
        }, null)
        return entries.size
    }

    /** Takes the ghosts away now. */
    fun clear(player: Player) {
        player.scheduler.run(plugin, { clearGhost(player) }, null)
    }

    @Suppress("UnstableApiUsage")
    private fun real(ghost: Ghost): Map<Position, BlockData> = ghost.entries.associate {
        it.at.toBlock() to if (Bukkit.isOwnedByCurrentRegion(it.at)) it.at.block.blockData else it.was
    }

    @Suppress("UnstableApiUsage")
    private fun clearGhost(player: Player) {
        val ghost = ghosts.remove(player.uniqueId) ?: return
        ghost.task?.cancel()
        if (player.world != ghost.world) return
        player.sendMultiBlockChange(real(ghost))
    }

    private companion object {
        const val HALF_TICKS = 20L
    }
}
