package com.tracel.plugin.bukkit.entity

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.entity.EntityExtras
import com.tracel.plugin.util.runtime.Warnings
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LeashHitch
import org.bukkit.entity.Painting
import org.bukkit.entity.Zombie
import org.bukkit.entity.ZombieVillager
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import java.util.*
import java.util.logging.Logger

/**
 * Armor stand poses.
 *
 * @see ArmorStand
 */
@Unstable
internal object ArmorStandPose : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is ArmorStand || ghost !is ArmorStand) return
        live.headPose = ghost.headPose
        live.bodyPose = ghost.bodyPose
        live.leftArmPose = ghost.leftArmPose
        live.rightArmPose = ghost.rightArmPose
        live.leftLegPose = ghost.leftLegPose
        live.rightLegPose = ghost.rightLegPose
        live.isSmall = ghost.isSmall
        live.setArms(ghost.hasArms())
        live.setBasePlate(ghost.hasBasePlate())
        live.isMarker = ghost.isMarker
        live.isInvisible = ghost.isInvisible
    }
}

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
            ) { "Could not spawn a falling ${data.asString} (${it.message})." }
            null
        }

    fun spawnFromExtras(world: World, loc: Location, extras: EntityExtras.Falling, logger: Logger): Entity? {
        val data = runCatching { Bukkit.createBlockData(extras.data.value) }.getOrNull()
        if (data != null) return spawn(world, loc, data, logger)
        Warnings.once(logger, "falling:${extras.data.value}") { "Unreadable falling block ${extras.data.value}." }
        return null
    }
}

/**
 * The item shown in an item frame.
 *
 * @see ItemFrame
 */
@Unstable
internal object ItemFrameCargo : CargoSurface {
    override fun matches(entity: Entity): Boolean = entity is ItemFrame

    override fun collect(entity: Entity, add: (ItemStack?) -> Unit) {
        addIfPresent((entity as ItemFrame).item, add)
    }

    override fun empty(entity: Entity) {
        runCatching { (entity as ItemFrame).setItem(ItemStack.empty(), false) }
    }

    override fun save(entity: Entity): Any = (entity as ItemFrame).item.clone()

    override fun restore(entity: Entity, saved: Any?) {
        val item = saved as? ItemStack ?: return
        (entity as ItemFrame).setItem(item, false)
    }

    override fun resync(entity: Entity, plugin: Plugin) {
        entity.hideThenShow(plugin)
    }
}

/**
 * Item frame state.
 *
 * @see ItemFrame
 */
@Unstable
internal object ItemFrameState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is ItemFrame || ghost !is ItemFrame) return
        live.rotation = ghost.rotation
    }
}

/**
 * Leash knot. Vanilla keeps one hitch per block and reuses it.
 *
 * @see LeashHitch
 */
@Unstable
internal object LeashKnotAdapter {
    fun isType(type: String): Boolean {
        val key = NamespacedKey.fromString(type) ?: return type.substringAfter(':') == "leash_knot"
        return key.key == "leash_knot"
    }

    fun at(world: World, loc: Location): Entity? = runCatching {
        world.getNearbyEntities(BoundingBox.of(loc.block))
            .firstOrNull { it is LeashHitch && it.isValid }
    }.getOrNull()
}

/**
 * Painting art.
 *
 * @see Painting
 */
@Unstable
internal object PaintingArt : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is Painting || ghost !is Painting) return
        runCatching { live.setArt(ghost.art, true) }
    }
}

/**
 * Entity on a shoulder (like parrots on vanilla).
 *
 * @see HumanEntity
 */
@Unstable
internal object ShoulderAdapter {
    private const val SEARCH_RADIUS = 48.0

    @Suppress("DEPRECATION") // Yes, shoulderEntityX is deprecated and it's strange
    fun takeOff(uuid: UUID, near: Location): Boolean {
        val world = near.world ?: return false
        for (player in world.getNearbyPlayers(near, SEARCH_RADIUS)) {
            if (!Bukkit.isOwnedByCurrentRegion(player)) continue
            if (runCatching { player.shoulderEntityLeft?.uniqueId }.getOrNull() == uuid) {
                runCatching { player.shoulderEntityLeft = null }
                return true
            }
            if (runCatching { player.shoulderEntityRight?.uniqueId }.getOrNull() == uuid) {
                runCatching { player.shoulderEntityRight = null }
                return true
            }
        }
        return false
    }
}

/**
 * Abort in-flight type changes after an NBT spawn.
 *
 * A restored zombie may still be drowning into a drowned, or a zombie
 * villager finishing a golden-apple cure. Either replaces the entity on
 * the next tick.
 *
 * [Zombie.stopDrowning] is the drowning path. Curing a [ZombieVillager]
 * still goes through [Zombie.setConversionTime]: any negative value aborts
 * without converting.
 *
 * @see Zombie.setConversionTime
 */
@Unstable
internal object ZombieConversion {
    private const val ABORT = -1

    fun halt(entity: Entity) {
        if (entity !is Zombie) return
        runCatching { entity.stopDrowning() }
        runCatching { entity.conversionTime = ABORT }
    }
}
