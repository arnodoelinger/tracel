package com.tracel.api.addon

import com.tracel.api.Unstable
import com.tracel.api.world.NamespacedName
import org.bukkit.block.data.BlockData

/**
 * Sees custom blocks for what they are.
 *
 * Called from any thread, lookups render wherever they please.
 *
 * A lens reads the data it is given, never the world.
 */
@Unstable
public interface BlockLens {
    /** The custom block [data] stands for, or `null` for a plain one. */
    public fun name(data: BlockData): NamespacedName?

    /** What custom block [name] is made of, or `null` when this lens does not know it. */
    public fun data(name: NamespacedName): BlockData?
}

/** Where lenses are registered. */
@Unstable
public interface Lenses {
    /**
     * Registers [lens] until the addon is disabled.
     *
     * When two lenses know a block, the first registered wins.
     */
    public fun block(lens: BlockLens)
}
