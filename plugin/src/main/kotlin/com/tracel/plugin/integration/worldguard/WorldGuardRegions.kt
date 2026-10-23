package com.tracel.plugin.integration.worldguard

import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.managers.RegionManager
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import com.sk89q.worldguard.protection.regions.RegionType
import com.tracel.api.world.BlockPlace
import org.bukkit.World
import org.bukkit.entity.Player

/** The `WorldGuard` half of [Regions]. */
internal object WorldGuardRegions {
    private fun managerOf(world: World): RegionManager? =
        WorldGuard.getInstance().platform.let { platform ->
            platform.matcher.getWorldByName(world.name)?.let(platform.regionContainer::get)
        }

    fun find(world: World, name: String): RegionBox? {
        val manager = managerOf(world) ?: return null
        val region = manager.getRegion(name) ?: manager.regions.values.firstOrNull { it.id.equals(name, true) }
        return region?.let { box(world, it) }
    }

    fun nearby(player: Player, typed: String, limit: Int): List<String> {
        val manager = managerOf(player.world) ?: return emptyList()
        val at = player.location
        return manager.regions.values.asSequence()
            .filter { it.type != RegionType.GLOBAL && it.id.contains(typed, ignoreCase = true) }
            .map { it to distance(it, at.blockX, at.blockY, at.blockZ) }
            .sortedWith(compareBy({ it.second }, { it.first.id }))
            .take(limit)
            .map { it.first.id }
            .toList()
    }

    private fun box(world: World, region: ProtectedRegion): RegionBox {
        val min = region.minimumPoint
        val max = region.maximumPoint
        return RegionBox(
            id = region.id,
            world = world,
            min = BlockPlace(world.uid, min.x(), min.y(), min.z()),
            max = BlockPlace(world.uid, max.x(), max.y(), max.z()),
            cuboid = region.type == RegionType.CUBOID,
            whole = region.type == RegionType.GLOBAL,
        )
    }

    private fun distance(region: ProtectedRegion, x: Int, y: Int, z: Int): Long {
        val min = region.minimumPoint
        val max = region.maximumPoint
        fun gap(at: Int, low: Int, high: Int): Long = maxOf(low - at, 0, at - high).toLong()
        val dx = gap(x, min.x(), max.x())
        val dy = gap(y, min.y(), max.y())
        val dz = gap(z, min.z(), max.z())
        return dx * dx + dy * dy + dz * dz
    }
}
