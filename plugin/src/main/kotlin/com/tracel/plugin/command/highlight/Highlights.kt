package com.tracel.plugin.command.highlight

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.plugin.adapter.block.BlockDataCache
import org.bukkit.Bukkit
import org.bukkit.Location
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

    private class Ghost(val world: org.bukkit.World, val restore: Map<Location, BlockData>)

    /**
     * Shows what the rollback would put back as it would look, to [player] alone, for [seconds].
     * Only blocks; entities and items have no ghost.
     *
     * @return how many blocks were drawn.
     */
    fun ghost(player: Player, steps: List<StructureStep>, seconds: Int): Int {
        val world = player.world
        val draw = LinkedHashMap<Location, BlockData>()
        val restore = LinkedHashMap<Location, BlockData>()
        for (step in steps) {
            if (draw.size >= MAX_GHOSTS) break
            val set = step as? StructureStep.SetBlock ?: continue
            val target = BlockDataCache.of(set.target.data) ?: continue
            val was = BlockDataCache.of(set.expected.data) ?: continue
            val at = Location(world, set.at.x.toDouble(), set.at.y.toDouble(), set.at.z.toDouble())
            draw[at] = target
            restore[at] = was
        }
        if (draw.isEmpty()) return 0
        player.scheduler.run(plugin, {
            clearGhost(player)
            ghosts[player.uniqueId] = Ghost(world, restore)
            for ((at, data) in draw) player.sendBlockChange(at, data)
            player.scheduler.runDelayed(plugin, { clearGhost(player) }, null, seconds * 20L)
        }, null)
        return draw.size
    }

    /** Takes the ghosts away now. */
    fun clear(player: Player) {
        player.scheduler.run(plugin, { clearGhost(player) }, null)
    }

    private fun clearGhost(player: Player) {
        val ghost = ghosts.remove(player.uniqueId) ?: return
        if (player.world != ghost.world) return
        for ((at, planned) in ghost.restore) {
            val real = if (Bukkit.isOwnedByCurrentRegion(at)) at.block.blockData else planned
            player.sendBlockChange(at, real)
        }
    }

    private companion object {
        const val MAX_GHOSTS = 4_000
    }
}
