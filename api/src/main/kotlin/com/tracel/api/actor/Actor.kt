package com.tracel.api.actor

import com.tracel.api.Unstable
import com.tracel.api.addon.Addon
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.NamespacedName
import java.util.UUID
import org.bukkit.entity.Entity as BukkitEntity
import org.bukkit.entity.Player as BukkitPlayer

/**
 * Who did something.
 *
 * Is's never a live [Player], but an ID, so it can cross threads and outlive a logout.
 */
@Unstable
public sealed interface Actor {
    /** A player, online or not. */
    public data class Player(public val uuid: UUID) : Actor

    /** Any other entity: a creeper, a villager, a minecart. */
    public data class Entity(public val uuid: UUID) : Actor

    /** A block doing the work: a hopper, a dispenser, a piston. */
    public data class Block(public val place: BlockPlace) : Actor

    /** A plugin acting on its own, shown in lookups as `name (plugin)`. */
    public data class Plugin(public val name: String) : Actor

    /** Something an addon defined: an NPC, a town, a bot. [kind] names the addon's actor kind. */
    public data class Addon(public val kind: NamespacedName, public val id: String) : Actor

    /** Nobody: fire, water, growth, the world being the world. */
    public data object Natural : Actor

    public companion object {
        @JvmStatic
        public fun of(entity: BukkitEntity): Actor =
            if (entity is BukkitPlayer) Player(entity.uniqueId) else Entity(entity.uniqueId)
    }
}

/** This entity as an [Actor]. */
@Unstable
public val BukkitEntity.actor: Actor get() = Actor.of(this)
