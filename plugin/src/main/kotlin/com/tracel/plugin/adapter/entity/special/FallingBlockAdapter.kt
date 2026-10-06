package com.tracel.plugin.adapter.entity.special

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.entity.EntityExtras
import com.tracel.plugin.util.log.Warnings
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import java.util.logging.Logger

/**
 * Falling block (block in mid-air).
 *
 * @see FallingBlock
 */
@Unstable
internal object FallingBlockAdapter {
    fun extrasOf(entity: Entity): EntityExtras? {
        if (entity !is FallingBlock) return null
        return runCatching { EntityExtras.Falling(BlockDataKey(entity.blockData.asString)) }.getOrNull()
    }

    fun isType(type: String): Boolean {
        val key = NamespacedKey.fromString(type) ?: return type.substringAfter(':') == "falling_block"
        return key.key == "falling_block"
    }

    fun spawn(world: World, loc: Location, data: BlockData, logger: Logger): FallingBlock? =
        runCatching {
            world.spawn(loc, FallingBlock::class.java) { falling ->
                falling.blockData = data
                falling.dropItem = false
            }
        }.getOrElse {
            Warnings.once(
                logger,
                "spawn-falling:${data.asString}",
            ) { "could not spawn a falling ${data.asString}: ${it.message}" }
            null
        }

    fun spawnFromExtras(world: World, loc: Location, extras: EntityExtras.Falling, logger: Logger): Entity? {
        val data = runCatching { Bukkit.createBlockData(extras.data.value) }.getOrNull()
        if (data != null) return spawn(world, loc, data, logger)
        Warnings.once(logger, "falling:${extras.data.value}") { "unreadable falling block ${extras.data.value}" }
        return null
    }
}
