package com.tracel.api.addon

import com.tracel.api.Unstable
import com.tracel.api.world.NamespacedName
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack

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

/**
 * Sees custom items for what they are: `itemsadder:ruby` instead of a paper with a model number.
 *
 * Lookups show the name, and `i:itemsadder:ruby` finds every stack the lens calls that, renamed or not.
 *
 * Called from any thread, with a copy of the stack as history keeps it; a lens reads it and nothing else.
 */
@Unstable
public interface ItemLens {
    /** The custom item [item] is, or `null` for a plain one. */
    public fun name(item: ItemStack): NamespacedName?
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

    /**
     * Registers [lens] until the addon is disabled.
     *
     * When two lenses know an item, the first registered wins.
     */
    public fun item(lens: ItemLens)
}
