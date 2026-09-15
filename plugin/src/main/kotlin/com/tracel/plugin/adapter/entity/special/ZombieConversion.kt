package com.tracel.plugin.adapter.entity.special

import com.tracel.annotations.Unstable
import org.bukkit.entity.Entity
import org.bukkit.entity.Zombie
import org.bukkit.entity.ZombieVillager
import org.bukkit.entity.PigZombie

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
