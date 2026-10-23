package com.tracel.plugin.integration.worldguard

import com.tracel.api.world.BlockPlace
import com.tracel.api.world.LookupArea
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("Regions")

/**
 * A `WorldGuard` region as a box in [world].
 *
 * Only [cuboid] regions fill their box; the box of any other shape holds blocks outside the region.
 */
internal data class RegionBox(
    val id: String,
    val world: World,
    val min: BlockPlace,
    val max: BlockPlace,
    val cuboid: Boolean,
    val whole: Boolean,
) {
    val area: LookupArea; get() = if (whole) LookupArea.World(world.uid) else LookupArea.Cube(min, max)
}

/** Looks regions up when `WorldGuard` is on the server. */
internal object Regions {
    private const val PLUGIN = "WorldGuard"

    val available: Boolean get() = Bukkit.getPluginManager().isPluginEnabled(PLUGIN)

    /** The region called [name], in [prefer] if it has one, else in the first world that does. */
    fun find(name: String, prefer: World?): RegionBox? = guarded {
        val worlds = listOfNotNull(prefer) + Bukkit.getWorlds().filter { it != prefer }
        worlds.firstNotNullOfOrNull { WorldGuardRegions.find(it, name) }
    }

    /**
     * IDs of regions in [player]'s world that contain [typed], the nearest to the player first,
     * at most [limit].
     */
    fun nearby(player: Player, typed: String, limit: Int): List<String> =
        guarded { WorldGuardRegions.nearby(player, typed, limit) } ?: emptyList()

    private inline fun <T> guarded(read: () -> T): T? {
        if (!available) return null
        return try {
            read()
        } catch (failure: Throwable) {
            logger.log(Level.FINE, "WorldGuard regions could not be read.", failure)
            null
        }
    }
}
