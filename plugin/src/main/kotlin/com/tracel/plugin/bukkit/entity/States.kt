package com.tracel.plugin.bukkit.entity

import com.tracel.annotations.Unstable
import io.papermc.paper.entity.CollarColorable
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Sittable
import org.bukkit.entity.Tameable
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryHolder

/** Every in-place setter, in an order that does not fight itself. */
@Unstable
internal object InPlaceStates {
    // Warning: do not change the order
    private val all: List<InPlaceState> = listOf(
        BaseEntityState,
        HangingState,
        ItemFrameState,
        PaintingArt,
        ArmorStandPose,
        LivingState,
        AgeableState,
        TameableState,
        SittableState,
        CollarState,
        WornEquipmentState,
    )

    fun apply(live: Entity, ghost: Entity) {
        for (state in all) state.apply(live, ghost)
    }
}

/**
 * Baby / adult age.
 *
 * @see [Ageable].
 */
internal object AgeableState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is Ageable || ghost !is Ageable) return
        runCatching { live.age = ghost.age }
    }
}

/**
 * Fire, freeze, name, glow, etc. All entity states.
 *
 * @see [Entity]
 */
internal object BaseEntityState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        runCatching {
            live.fireTicks = ghost.fireTicks
            live.freezeTicks = ghost.freezeTicks
            live.isInvulnerable = ghost.isInvulnerable
            live.isGlowing = ghost.isGlowing
            live.isSilent = ghost.isSilent
            live.customName(ghost.customName())
            live.isCustomNameVisible = ghost.isCustomNameVisible
        }
    }
}

/**
 * Collar dye.
 *
 * @see [CollarColorable]
 */
internal object CollarState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is CollarColorable || ghost !is CollarColorable) return
        runCatching { live.collarColor = ghost.collarColor }
    }
}

/**
 * Which wall a hanging is nailed to.
 *
 * Teleporting a painting moves the nail. Facing is the only setter that
 * keeps the art on the block it was recorded against.
 *
 * @see [Hanging]
 */
internal object HangingState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is Hanging || ghost !is Hanging) return
        runCatching { live.setFacingDirection(ghost.facing, true) }
    }
}

/**
 * Copy one `Paper` capability from a deserialized ghost onto the hull that is
 * still standing.
 *
 * @see [Entity]
 */
internal fun interface InPlaceState {
    /** Apply capability. */
    fun apply(live: Entity, ghost: Entity)
}

/**
 * Health, air, AI, potions.
 *
 * @see [LivingEntity]
 */
internal object LivingState : InPlaceState {
    private const val DEAD_HEALTH = 0.0

    override fun apply(live: Entity, ghost: Entity) {
        if (live !is LivingEntity || ghost !is LivingEntity) return
        runCatching {
            val health = ghost.health
            val ceiling = live.getAttribute(Attribute.MAX_HEALTH)?.value ?: health

            // Health of 0 is a dead body: restoring it plays the death animation and dumps
            // loot again.
            if (health > DEAD_HEALTH) live.health = health.coerceAtMost(ceiling)
        }
        runCatching { live.remainingAir = ghost.remainingAir }
        runCatching { live.setAI(ghost.hasAI()) }
        runCatching {
            for (effect in live.activePotionEffects) live.removePotionEffect(effect.type)
            for (effect in ghost.activePotionEffects) live.addPotionEffect(effect)
        }
    }
}

/**
 * Sit / stand. Wolves, cats, and whoever else.
 *
 * @see [Sittable]
 */
internal object SittableState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is Sittable || ghost !is Sittable) return
        runCatching { live.isSitting = ghost.isSitting }
    }
}

/**
 * Owner and tamed flag.
 *
 * @see [Tameable]
 */
internal object TameableState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is Tameable || ghost !is Tameable) return
        runCatching {
            live.isTamed = ghost.isTamed
            live.owner = ghost.owner
        }
    }
}

/**
 * Armor a mob is wearing, copied onto a hull that we reused.
 *
 * @see [EquipmentAsCargo]
 */
internal object WornEquipmentState : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (EquipmentAsCargo.matches(live) || EquipmentAsCargo.matches(ghost)) return
        if (live is InventoryHolder) return
        if (live !is LivingEntity || ghost !is LivingEntity) return
        val worn = ghost.equipment ?: return
        val wearing = live.equipment ?: return
        val owned = (live as? Mob)?.let { MobEquipmentCargo.owned(it) }.orEmpty().toSet()
        for (slot in EquipmentSlot.entries) {
            if (slot in owned) continue
            runCatching { wearing.setItem(slot, worn.getItem(slot)) }
        }
    }
}
