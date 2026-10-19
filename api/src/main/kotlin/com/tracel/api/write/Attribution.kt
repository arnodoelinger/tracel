package com.tracel.api.write

import com.tracel.api.Unstable
import com.tracel.api.actor.Actor
import com.tracel.api.result.Res
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.NamespacedName
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Changing the world through `Tracel`, for a plugin granted `attribute`.
 *
 * A plain `block.type = STONE` leaves a hole in history that a later rollback cannot see;
 * these write the change and its record together, so nothing slips by.
 *
 * Example:
 * ```
 * with(attribution) {
 *     arenaFloor.forEach { it.set(sand) }
 *     winner.give(trophy, by = winner.actor)
 * }
 * ```
 *
 * `by` is whose doing it was, the plugin's own when left out. Lookups show either with the plugin as cause.
 * A player, an entity, a block or the plugin itself can be named; another plugin cannot.
 */
@Unstable
public interface Attribution {
    /** Sets this block to [data], on the region that owns it, and writes the change down. */
    public suspend fun BlockPlace.set(data: BlockData, by: Actor? = null): Res<Unit, WriteError>

    /**
     * Puts [stack] into this player's inventory and writes down that it came from the plugin.
     *
     * What did not fit is not given; the answer is how many that were.
     */
    public suspend fun Player.give(stack: ItemStack, by: Actor? = null): Res<Int, WriteError>

    /**
     * Writes down an event of the plugin's own, found in lookups and as
     * `EventKind.CUSTOM` records.
     */
    public suspend fun record(
        name: NamespacedName,
        by: Actor? = null,
        at: BlockPlace? = null,
        text: String? = null,
    ): Res<Unit, WriteError>
}
