package com.tracel.api.addon

import com.tracel.api.Unstable
import com.tracel.api.actor.Actor
import com.tracel.api.item.ItemHolder
import com.tracel.api.world.BlockPlace
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player

/**
 * Writing down what no `Bukkit` event reports. An addon never listens for raw events to log them; it tells `Tracel`
 * here, and `Tracel` keeps the order with everything else it captures.
 */
@Unstable
public interface AddonRecorder {
    /**
     * Runs [action] and writes down what it did to [holder]'s contents and, when one is named, to [player]'s
     * inventory, as one move by [player].
     *
     * Call it on the region thread that owns [player], or the holder's place without one: both are read before and
     * after [action], right there.
     *
     * @return what [action] returned
     */
    public fun <T> watching(holder: ItemHolder.Custom, player: Player? = null, action: () -> T): T

    /**
     * A batch of block changes no `Bukkit` event will report, written down as [by]'s doing. It also flushes on its
     * own when the addon is disabled.
     */
    public fun batch(by: Actor): BlockBatch
}

/** Block changes waiting to be written down. Safe to note from several threads. */
@Unstable
public interface BlockBatch : AutoCloseable {
    /**
     * [place] went from [before] to [after]. A place noted twice is one change:
     * the first before, the last after.
     */
    public fun block(place: BlockPlace, before: BlockData, after: BlockData)

    /** Writes down everything noted so far. */
    public fun flush()

    /** Flushes, and the batch is done: later notes are dropped. */
    override fun close()
}
